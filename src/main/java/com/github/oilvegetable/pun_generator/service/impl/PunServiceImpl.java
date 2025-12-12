package com.github.oilvegetable.pun_generator.service.impl;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.text.StrSplitter;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.oilvegetable.pun_generator.config.PunProperties;
import com.github.oilvegetable.pun_generator.vo.PunResult;
import com.github.oilvegetable.pun_generator.service.PunService;
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
        List<String> pinyins;
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
        // 如果配置文件里配了 search-order，就用配置的；否则默认优先级为 MAX_VALUE (排在最后)
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
        // 复制一份所有类型
        List<String> sortedTypes = new ArrayList<>(allTypes);
        // 按照 typePriorityMap 进行排序
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

        // 规则：配置了优先级的按配置走，没配置的按默认顺序排在后面
        searchTypes.sort(Comparator.comparingInt(t -> typePriorityMap.getOrDefault(t, Integer.MAX_VALUE)));

        for (String type : searchTypes) {
            resultMap.putIfAbsent(type, new ArrayList<>());
        }

        if (inputWord == null || inputWord.isEmpty()) return resultMap;
        List<String> inputPinyins = getStringPinyins(inputWord);
        if (inputPinyins.isEmpty()) return resultMap;

        // 1. 粗筛
        Set<DictItem> candidates = new HashSet<>();
        for (String py : inputPinyins) {
            if (invertedIndex.containsKey(py)) {
                Set<DictItem> items = invertedIndex.get(py);
                for (DictItem item : items) {
                    if (resultMap.containsKey(item.getType())) { // 注意：Lombok生成的Getter是 getType()
                        candidates.add(item);
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
        List<String> pinyins = getStringPinyins(text);
        if (pinyins.isEmpty()) return;
        DictItem item = new DictItem(text, type, extra, frequency, pinyins);
        for (String py : pinyins) {
            invertedIndex.computeIfAbsent(py, k -> new HashSet<>()).add(item);
        }
    }

    // 有序匹配
    private MatchResult calculateOrderedMatch(String inputWord, List<String> inputPinyins, DictItem item) {
        List<String> idiomPinyins = item.getPinyins();
        if (idiomPinyins.size() < inputPinyins.size()) return null;

        int bestScore = -1;
        List<Integer> bestIndices = null;
        List<Integer> startPositions = new ArrayList<>();

        String firstPy = inputPinyins.get(0);
        for (int i = 0; i < idiomPinyins.size(); i++) {
            if (idiomPinyins.get(i).equals(firstPy)) startPositions.add(i);
        }

        for (int startIdx : startPositions) {
            List<Integer> currentIndices = new ArrayList<>();
            currentIndices.add(startIdx);
            int currentIdiomSearchIdx = startIdx + 1;
            boolean possible = true;
            int currentScore = 10;

            for (int k = 1; k < inputPinyins.size(); k++) {
                String targetPy = inputPinyins.get(k);
                int foundAt = -1;
                for (int m = currentIdiomSearchIdx; m < idiomPinyins.size(); m++) {
                    if (idiomPinyins.get(m).equals(targetPy)) {
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

    // 无序匹配
    private MatchResult calculateUnorderedMatch(String inputWord, List<String> inputPinyins, DictItem item) {
        List<String> idiomPinyins = item.getPinyins();
        if (idiomPinyins.size() < inputPinyins.size()) return null;

        List<Integer> resultIndices = new ArrayList<>();
        // 使用一个标记数组，防止同一个位置被重复使用
        boolean[] used = new boolean[idiomPinyins.size()];
        int score = 0;

        // 贪心匹配：为每一个输入拼音找到第一个未使用的匹配项
        for (String inputPy : inputPinyins) {
            int foundAt = -1;
            for (int i = 0; i < idiomPinyins.size(); i++) {
                if (!used[i] && idiomPinyins.get(i).equals(inputPy)) {
                    foundAt = i;
                    break;
                }
            }

            if (foundAt != -1) {
                used[foundAt] = true;
                resultIndices.add(foundAt);
                score += 10; // 基础分
            } else {
                // 如果有一个拼音找不到，说明无法构成谐音，直接失败
                return null;
            }
        }

        // 构造结果
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

    private List<String> getStringPinyins(String str) {
        List<String> list = new ArrayList<>();
        for (char c : str.toCharArray()) {
            String[] pinyins = null;
            try { pinyins = PinyinHelper.toHanyuPinyinStringArray(c); } catch (Exception e) {}
            if (pinyins != null && pinyins.length > 0) list.add(pinyins[0].replaceAll("\\d", ""));
        }
        return list;
    }
}