package com.github.oilvegetable.pun_generator.service.impl;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.text.StrSplitter;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.oilvegetable.pun_generator.config.PunProperties;
import com.github.oilvegetable.pun_generator.service.PunService;
import com.github.oilvegetable.pun_generator.vo.PunResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import net.sourceforge.pinyin4j.PinyinHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PunServiceImpl implements PunService {

    @Autowired
    private PunProperties punProperties;

    private final Map<String, List<String>> categoryMap = new LinkedHashMap<>();
    private final Map<String, Set<DictItem>> invertedIndex = new HashMap<>();
    private final Set<String> allTypes = new LinkedHashSet<>();
    private final Map<String, Integer> typePriorityMap = new HashMap<>();

    @Data
    @AllArgsConstructor
    private static class DictItem {
        String text;
        String type;
        String extra;
        int frequency;
        // 集合是为了处理多音字
        List<Set<String>> pinyins;
    }

    @Data
    @AllArgsConstructor
    private static class MatchResult {
        String finalPun;
        int score;
        List<Integer> indices;
    }

    @Data
    private static class MergedResult {
        String finalPun;
        List<Integer> indices;
        int maxScore;
        int maxFrequency;
        Set<String> originTexts = new LinkedHashSet<>();

        public MergedResult(String finalPun, List<Integer> indices, int score, int frequency, String originText) {
            this.finalPun = finalPun;
            this.indices = indices;
            this.maxScore = score;
            this.maxFrequency = frequency;
            this.originTexts.add(originText);
        }

        public void addOrigin(String originText, int score, int frequency) {
            this.originTexts.add(originText);
            this.maxScore = Math.max(this.maxScore, score);
            this.maxFrequency = Math.max(this.maxFrequency, frequency);
        }

        public PunResult toPunResult() {
            return new PunResult(this.finalPun, this.originTexts, this.indices);
        }
    }

    @PostConstruct
    @Override
    public void initData() {
        System.out.println("正在根据配置文件构建索引...");

        if (punProperties.getGroups() == null) {
            System.err.println("未找到词库配置");
            return;
        }

        // 1. 清理数据
        categoryMap.clear();
        invertedIndex.clear();
        allTypes.clear();
        typePriorityMap.clear();

        // 2. 初始化优先级映射
        List<String> orderConfig = punProperties.getSearchOrder();
        if (orderConfig != null) {
            for (int i = 0; i < orderConfig.size(); i++) {
                typePriorityMap.put(orderConfig.get(i), i);
            }
        }

        // 3. 加载词库
        for (PunProperties.GroupConfig group : punProperties.getGroups()) {
            categoryMap.put(group.getName(), new ArrayList<>());
            if (group.getDicts() == null) continue;

            for (PunProperties.DictConfig dict : group.getDicts()) {
                System.out.println("加载: " + dict.getName());
                categoryMap.get(group.getName()).add(dict.getName());
                allTypes.add(dict.getName());

                if (dict.getLoaderType() == null) continue;
                switch (dict.getLoaderType()) {
                    case JSON_NORMAL: loadJsonNormal(dict); break;
                    case JSON_XIEHOUYU: loadJsonXiehouyu(dict); break;
                    case THUOCL: loadThuoclData(dict); break;
                }
            }
        }
        System.out.println("初始化完成，共 " + allTypes.size() + " 个分类");
    }

    @Override
    public Map<String, List<String>> getCategoryMap() {
        return categoryMap;
    }

    @Override
    public List<String> getAllTypesOrdered() {
        List<String> sortedTypes = new ArrayList<>(allTypes);
        sortedTypes.sort(Comparator.comparingInt(t -> typePriorityMap.getOrDefault(t, Integer.MAX_VALUE)));
        return sortedTypes;
    }

    @Override
    public List<String> getDefaultSelectedTypes() {
        return punProperties.getDefaultDictNames() != null ? punProperties.getDefaultDictNames() : new ArrayList<>();
    }

    @Override
    public Map<String, List<PunResult>> generatePun(String inputWord, List<String> targetTypes, boolean ignoreOrder) {
        Map<String, List<PunResult>> resultMap = new LinkedHashMap<>();
        List<String> searchTypes = (targetTypes == null || targetTypes.isEmpty())
                ? new ArrayList<>(allTypes)
                : new ArrayList<>(targetTypes);

        searchTypes.sort(Comparator.comparingInt(t -> typePriorityMap.getOrDefault(t, Integer.MAX_VALUE)));

        for (String type : searchTypes) {
            resultMap.putIfAbsent(type, new ArrayList<>());
        }

        if (inputWord == null || inputWord.isEmpty()) return resultMap;

        List<Set<String>> inputPinyins = getStringPinyins(inputWord);
        if (inputPinyins.isEmpty()) return resultMap;

        // 1. 粗筛
        Set<DictItem> candidates = new HashSet<>();
        for (Set<String> pinyinSet : inputPinyins) {
            // 输入字的任何一个读音匹配上，都把该词条拉入候选
            for (String py : pinyinSet) {
                if (invertedIndex.containsKey(py)) {
                    Set<DictItem> items = invertedIndex.get(py);
                    for (DictItem item : items) {
                        if (resultMap.containsKey(item.getType())) {
                            candidates.add(item);
                        }
                    }
                }
            }
        }

        // 2. 细算
        Map<String, Map<String, MergedResult>> tempGroupedMap = new HashMap<>();
        for (String type : searchTypes) {
            tempGroupedMap.put(type, new HashMap<>());
        }

        for (DictItem item : candidates) {
            MatchResult match;
            if (ignoreOrder) {
                match = calculateUnorderedMatch(inputWord, inputPinyins, item);
            } else {
                match = calculateOrderedMatch(inputWord, inputPinyins, item);
            }

            if (match != null) {
                Map<String, MergedResult> group = tempGroupedMap.get(item.getType());
                if (group != null) {
                    String fullPunDisplay = match.getFinalPun();
                    String fullOriginText = item.getText();
                    List<Integer> finalIndices = new ArrayList<>(match.getIndices());

                    if ("歇后语".equals(item.getType()) && StrUtil.isNotBlank(item.getExtra())) {
                        String prefix = item.getExtra() + "——";
                        fullPunDisplay = prefix + match.getFinalPun();
                        fullOriginText = prefix + item.getText();
                        int offset = prefix.length();
                        finalIndices.replaceAll(i -> i + offset);
                    }

                    if (group.containsKey(fullPunDisplay)) {
                        group.get(fullPunDisplay).addOrigin(fullOriginText, match.getScore(), item.getFrequency());
                    } else {
                        group.put(fullPunDisplay, new MergedResult(fullPunDisplay, finalIndices, match.getScore(), item.getFrequency(), fullOriginText));
                    }
                }
            }
        }

        // 3. 排序
        for (String type : searchTypes) {
            Map<String, MergedResult> punMap = tempGroupedMap.get(type);
            if (punMap == null || punMap.isEmpty()) continue;

            List<MergedResult> list = new ArrayList<>(punMap.values());
            list.sort((r1, r2) -> {
                if (Math.abs(r1.getMaxScore() - r2.getMaxScore()) > 15) return r2.getMaxScore() - r1.getMaxScore();
                return r2.getMaxFrequency() - r1.getMaxFrequency();
            });

            List<PunResult> resultList = list.stream().limit(20)
                    .map(MergedResult::toPunResult)
                    .collect(Collectors.toList());
            resultMap.put(type, resultList);
        }
        return resultMap;
    }

    private void loadJsonNormal(PunProperties.DictConfig config) {
        try {
            String jsonStr = ResourceUtil.readUtf8Str(config.getPath());
            if (jsonStr == null) return;
            JSONArray array = JSONUtil.parseArray(jsonStr);
            for (Object obj : array) {
                JSONObject json = (JSONObject) obj;
                String text = json.getStr(config.getKeyField());
                String extra = config.getExtraField() != null ? json.getStr(config.getExtraField()) : "";
                if (text != null && !text.trim().isEmpty()) {
                    addToIndex(text, config.getName(), extra, config.getDefaultFreq());
                }
            }
        } catch (Exception e) { System.err.println("Load Error: " + config.getPath()); }
    }

    private void loadJsonXiehouyu(PunProperties.DictConfig config) {
        try {
            String jsonStr = ResourceUtil.readUtf8Str(config.getPath());
            JSONArray array = JSONUtil.parseArray(jsonStr);
            for (Object obj : array) {
                JSONObject json = (JSONObject) obj;
                String riddle = json.getStr("riddle");
                String answer = json.getStr("answer");
                if (StrUtil.isNotBlank(riddle) && StrUtil.isNotBlank(answer)) {
                    addToIndex(answer, config.getName(), riddle, config.getDefaultFreq());
                }
            }
        } catch (Exception e) { System.err.println("Load Error: " + config.getPath()); }
    }

    private void loadThuoclData(PunProperties.DictConfig config) {
        try {
            String content = ResourceUtil.readUtf8Str(config.getPath());
            if (content == null) return;
            List<String> lines = StrSplitter.split(content, '\n', true, true);
            for (String line : lines) {
                List<String> parts = StrSplitter.split(line, "\t", true, true);
                if (parts.size() < 2) continue;
                String text = parts.get(0);
                if (text.length() < 2) continue;
                try {
                    int frequency = Integer.parseInt(parts.get(1));
                    addToIndex(text, config.getName(), "", frequency);
                } catch (NumberFormatException e) { /* ignore */ }
            }
        } catch (Exception e) { System.err.println("Load Error: " + config.getPath()); }
    }

    private void addToIndex(String text, String type, String extra, int frequency) {
        // 获取该词所有汉字的所有拼音组合
        List<Set<String>> pinyinsList = getStringPinyins(text);
        if (pinyinsList.isEmpty()) return;

        DictItem item = new DictItem(text, type, extra, frequency, pinyinsList);

        // 将该 Item 注册到它包含的所有拼音下
        for (Set<String> pinyinSet : pinyinsList) {
            for (String py : pinyinSet) {
                invertedIndex.computeIfAbsent(py, k -> new HashSet<>()).add(item);
            }
        }
    }

    private MatchResult calculateOrderedMatch(String inputWord, List<Set<String>> inputPinyins, DictItem item) {
        List<Set<String>> idiomPinyins = item.getPinyins();
        if (idiomPinyins.size() < inputPinyins.size()) return null;

        int bestScore = -1;
        List<Integer> bestIndices = null;
        List<Integer> startPositions = new ArrayList<>();

        // 寻找可能的起始点：输入的第一个字的拼音集合 vs 词条第i个字的拼音集合
        Set<String> firstInputPySet = inputPinyins.get(0);
        for (int i = 0; i < idiomPinyins.size(); i++) {
            // 判断两个集合是否有交集
            if (!Collections.disjoint(idiomPinyins.get(i), firstInputPySet)) {
                startPositions.add(i);
            }
        }

        for (int startIdx : startPositions) {
            List<Integer> currentIndices = new ArrayList<>();
            currentIndices.add(startIdx);
            int currentIdiomSearchIdx = startIdx + 1;
            boolean possible = true;
            int currentScore = 10;

            for (int k = 1; k < inputPinyins.size(); k++) {
                Set<String> targetPySet = inputPinyins.get(k);
                int foundAt = -1;

                // 在剩余部分寻找匹配
                for (int m = currentIdiomSearchIdx; m < idiomPinyins.size(); m++) {
                    if (!Collections.disjoint(idiomPinyins.get(m), targetPySet)) {
                        foundAt = m; break;
                    }
                }

                if (foundAt != -1) {
                    currentIndices.add(foundAt);
                    currentScore += 10;
                    if (foundAt == currentIndices.get(currentIndices.size() - 2) + 1) currentScore += 20;
                    currentIdiomSearchIdx = foundAt + 1;
                } else {
                    possible = false; break;
                }
            }
            if (possible && currentScore > bestScore) {
                bestScore = currentScore;
                bestIndices = new ArrayList<>(currentIndices);
            }
        }

        if (bestIndices == null) return null;
        String finalPun = constructPlainString(inputWord, item.getText(), bestIndices);
        return new MatchResult(finalPun, bestScore, bestIndices);
    }

    private MatchResult calculateUnorderedMatch(String inputWord, List<Set<String>> inputPinyins, DictItem item) {
        List<Set<String>> idiomPinyins = item.getPinyins();
        if (idiomPinyins.size() < inputPinyins.size()) return null;

        List<Integer> resultIndices = new ArrayList<>();
        boolean[] used = new boolean[idiomPinyins.size()];
        int score = 0;

        for (Set<String> inputPySet : inputPinyins) {
            int foundAt = -1;
            for (int i = 0; i < idiomPinyins.size(); i++) {
                if (!used[i] && !Collections.disjoint(idiomPinyins.get(i), inputPySet)) {
                    foundAt = i;
                    break;
                }
            }

            if (foundAt != -1) {
                used[foundAt] = true;
                resultIndices.add(foundAt);
                score += 10;
            } else {
                return null;
            }
        }

        String finalPun = constructPlainString(inputWord, item.getText(), resultIndices);
        return new MatchResult(finalPun, score, resultIndices);
    }


    private String constructPlainString(String inputWord, String itemText, List<Integer> indices) {
        StringBuilder sb = new StringBuilder(itemText);
        for (int k = 0; k < indices.size(); k++) {
            if (k < inputWord.length()) {
                sb.setCharAt(indices.get(k), inputWord.charAt(k));
            }
        }
        return sb.toString();
    }

    // 获取字符的所有无声调拼音并去重
    private List<Set<String>> getStringPinyins(String str) {
        List<Set<String>> list = new ArrayList<>();
        for (char c : str.toCharArray()) {
            Set<String> pinyinSet = new HashSet<>();
            String[] pinyins = null;
            try {
                pinyins = PinyinHelper.toHanyuPinyinStringArray(c);
            } catch (Exception e) {}

            if (pinyins != null && pinyins.length > 0) {
                for (String py : pinyins) {
                    // 去除声调数字，并加入集合去重
                    pinyinSet.add(py.replaceAll("\\d", ""));
                }
            } else {
                // 这里暂时忽略，如果完全没拼音就不加，但在上层调用会判断 size
            }
            if (!pinyinSet.isEmpty()) {
                list.add(pinyinSet);
            }
        }
        return list;
    }
}