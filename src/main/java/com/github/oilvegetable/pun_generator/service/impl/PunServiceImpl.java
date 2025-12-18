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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PunServiceImpl implements PunService {

    @Autowired
    private PunProperties punProperties;

    private final Map<String, List<String>> categoryMap = new LinkedHashMap<>();
    private final Map<String, Set<DictItem>> invertedIndex = new HashMap<>();
    private final Set<String> allTypes = new LinkedHashSet<>();
    private final Map<String, Integer> typePriorityMap = new HashMap<>();

    // 预编译正则，优化性能，防止内存溢出
    private static final Pattern TONE_PATTERN = Pattern.compile("\\d");

    @Data
    @AllArgsConstructor
    private static class DictItem {
        String text;
        String type;
        String extra;
        int frequency;
        List<Set<String>> pinyins;
    }

    @Data
    @AllArgsConstructor
    private static class MatchResult {
        String finalPun;
        int matchCount;
        int score;
        List<Integer> indices;
    }

    @Data
    private static class MergedResult {
        String finalPun;
        List<Integer> indices;
        int maxMatchCount;
        int maxScore;
        int maxFrequency;
        Set<String> originTexts = new LinkedHashSet<>();

        public MergedResult(String finalPun, List<Integer> indices, int matchCount, int score, int frequency, String originText) {
            this.finalPun = finalPun;
            this.indices = indices;
            this.maxMatchCount = matchCount;
            this.maxScore = score;
            this.maxFrequency = frequency;
            this.originTexts.add(originText);
        }

        public void addOrigin(String originText, int matchCount, int score, int frequency) {
            this.originTexts.add(originText);
            // 优先保留匹配字数更多的
            if (matchCount > this.maxMatchCount) {
                this.maxMatchCount = matchCount;
                this.maxScore = score;
                this.maxFrequency = frequency;
                this.indices = null; // indices 会随 finalPun 改变，这里简化处理，通常取高分者的
            } else if (matchCount == this.maxMatchCount) {
                this.maxScore = Math.max(this.maxScore, score);
                this.maxFrequency = Math.max(this.maxFrequency, frequency);
            }
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

        // 获取多音字组合
        List<Set<String>> inputPinyins = getStringPinyins(inputWord);
        if (inputPinyins.isEmpty()) return resultMap;

        // 1. 确定最低匹配数
        // 比如输入"已生一世" (4字), 配置min=2, 则limit=2
        // 比如输入"啊" (1字), 配置min=2, 则limit=1 (取最小值)
        int minLimit = Math.min(inputPinyins.size(), punProperties.getMinMatchCount());

        // 2. 候选集筛选 (使用Set去重)
        Set<DictItem> candidates = new HashSet<>();
        for (Set<String> pinyinSet : inputPinyins) {
            for (String py : pinyinSet) {
                if (invertedIndex.containsKey(py)) {
                    Set<DictItem> items = invertedIndex.get(py);
                    for (DictItem item : items) {
                        // 类型过滤
                        if (!resultMap.containsKey(item.getType())) continue;
                        // 长度剪枝：如果词条拼音数 < minLimit，绝对无法匹配成功，直接丢弃
                        if (item.getPinyins().size() < minLimit) continue;

                        candidates.add(item);
                    }
                }
            }
        }

        // 3. 细算与合并
        Map<String, Map<String, MergedResult>> tempGroupedMap = new HashMap<>();
        for (String type : searchTypes) {
            tempGroupedMap.put(type, new HashMap<>());
        }

        for (DictItem item : candidates) {
            MatchResult match;
            if (ignoreOrder) {
                match = calculateUnorderedMatch(inputWord, inputPinyins, item, minLimit);
            } else {
                match = calculateOrderedMatch(inputWord, inputPinyins, item, minLimit);
            }

            if (match != null) {
                Map<String, MergedResult> group = tempGroupedMap.get(item.getType());
                if (group != null) {
                    String fullPunDisplay = match.getFinalPun();
                    String fullOriginText = item.getText();
                    List<Integer> finalIndices = new ArrayList<>(match.getIndices());

                    if ("歇后语".equals(item.getType()) && StrUtil.isNotBlank(item.getExtra())) {
                        String prefix = item.getExtra() + "——";
                        // 歇后语也需要把前缀加回去
                        fullPunDisplay = prefix + match.getFinalPun();
                        fullOriginText = prefix + item.getText();
                        int offset = prefix.length();
                        finalIndices.replaceAll(i -> i + offset);
                    }

                    if (group.containsKey(fullPunDisplay)) {
                        group.get(fullPunDisplay).addOrigin(fullOriginText, match.getMatchCount(), match.getScore(), item.getFrequency());
                    } else {
                        // 【调用修复】参数顺序严格对应构造函数
                        group.put(fullPunDisplay, new MergedResult(
                                fullPunDisplay,     // String
                                finalIndices,       // List
                                match.getMatchCount(), // int
                                match.getScore(),      // int
                                item.getFrequency(),   // int
                                fullOriginText         // String
                        ));
                    }
                }
            }
        }

        // 4. 排序
        for (String type : searchTypes) {
            Map<String, MergedResult> punMap = tempGroupedMap.get(type);
            if (punMap == null || punMap.isEmpty()) continue;

            List<MergedResult> list = new ArrayList<>(punMap.values());
            list.sort((r1, r2) -> {
                // 优先级1: 匹配字数 (越多越好)
                if (r1.getMaxMatchCount() != r2.getMaxMatchCount()) {
                    return r2.getMaxMatchCount() - r1.getMaxMatchCount();
                }
                // 优先级2: 分数 (越高越好)
                if (Math.abs(r1.getMaxScore() - r2.getMaxScore()) > 10) {
                    return r2.getMaxScore() - r1.getMaxScore();
                }
                // 优先级3: 词频 (越高越好)
                return r2.getMaxFrequency() - r1.getMaxFrequency();
            });

            // 不再 limit(20)，全部返回给前端分页
            List<PunResult> resultList = list.stream()
                    .map(MergedResult::toPunResult)
                    .collect(Collectors.toList());
            resultMap.put(type, resultList);
        }
        return resultMap;
    }

    // ----------------- 核心算法区 -----------------

    /**
     * 有序匹配 (支持跳字匹配，替换字符)
     */
    private MatchResult calculateOrderedMatch(String inputWord, List<Set<String>> inputPinyins, DictItem item, int minLimit) {
        List<Set<String>> dictPinyins = item.getPinyins();
        MatchResult bestMatch = null;

        for (int i = 0; i < inputPinyins.size(); i++) {
            List<Integer> currentIndices = new ArrayList<>();
            Map<Integer, Character> replacementMap = new HashMap<>();

            int currentDictIdx = 0;
            int matchCount = 0;
            int score = 0;

            for (int j = i; j < inputPinyins.size(); j++) {
                Set<String> inPy = inputPinyins.get(j);
                int foundAt = -1;
                for (int k = currentDictIdx; k < dictPinyins.size(); k++) {
                    if (!Collections.disjoint(dictPinyins.get(k), inPy)) {
                        foundAt = k;
                        break;
                    }
                }

                if (foundAt != -1) {
                    matchCount++;
                    currentIndices.add(foundAt);
                    // 记录替换：字典第 foundAt 个字 -> 输入的第 j 个字
                    replacementMap.put(foundAt, inputWord.charAt(j));

                    score += 10;
                    if (currentIndices.size() > 1 && foundAt == currentIndices.get(currentIndices.size() - 2) + 1) {
                        score += 20; // 连贯加分
                    }
                    currentDictIdx = foundAt + 1;
                }
            }

            if (matchCount >= minLimit) {
                if (bestMatch == null || matchCount > bestMatch.getMatchCount() || (matchCount == bestMatch.getMatchCount() && score > bestMatch.getScore())) {
                    String finalPun = constructPlainString(item.getText(), replacementMap);
                    bestMatch = new MatchResult(finalPun, matchCount, score, new ArrayList<>(currentIndices));
                }
            }
        }
        return bestMatch;
    }

    /**
     * 无序匹配 (贪心算法，替换字符)
     */
    private MatchResult calculateUnorderedMatch(String inputWord, List<Set<String>> inputPinyins, DictItem item, int minLimit) {
        List<Set<String>> dictPinyins = item.getPinyins();
        List<Integer> resultIndices = new ArrayList<>();
        Map<Integer, Character> replacementMap = new HashMap<>();
        boolean[] usedDict = new boolean[dictPinyins.size()];
        int matchCount = 0;
        int score = 0;

        for (int j = 0; j < inputPinyins.size(); j++) {
            Set<String> inPy = inputPinyins.get(j);
            for (int k = 0; k < dictPinyins.size(); k++) {
                if (!usedDict[k] && !Collections.disjoint(dictPinyins.get(k), inPy)) {
                    usedDict[k] = true;
                    resultIndices.add(k);
                    replacementMap.put(k, inputWord.charAt(j));
                    matchCount++;
                    score += 10;
                    break;
                }
            }
        }

        if (matchCount >= minLimit) {
            Collections.sort(resultIndices);
            String finalPun = constructPlainString(item.getText(), replacementMap);
            return new MatchResult(finalPun, matchCount, score, resultIndices);
        }
        return null;
    }

    /**
     * 字符串替换工具：将 itemText 中被匹配的字替换为 input 中的字
     */
    private String constructPlainString(String originText, Map<Integer, Character> replacementMap) {
        if (replacementMap == null || replacementMap.isEmpty()) {
            return originText;
        }
        StringBuilder sb = new StringBuilder(originText);
        for (Map.Entry<Integer, Character> entry : replacementMap.entrySet()) {
            int index = entry.getKey();
            char newChar = entry.getValue();
            if (index >= 0 && index < sb.length()) {
                sb.setCharAt(index, newChar);
            }
        }
        return sb.toString();
    }

    // ----------------- 加载与工具区 -----------------

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
        List<Set<String>> pinyinsList = getStringPinyins(text);
        if (pinyinsList.isEmpty()) return;

        DictItem item = new DictItem(text, type, extra, frequency, pinyinsList);
        for (Set<String> pinyinSet : pinyinsList) {
            for (String py : pinyinSet) {
                invertedIndex.computeIfAbsent(py, k -> new HashSet<>()).add(item);
            }
        }
    }

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
                    // 优化：intern() 复用字符串，降低内存
                    String clearPy = TONE_PATTERN.matcher(py).replaceAll("").intern();
                    pinyinSet.add(clearPy);
                }
            }
            if (!pinyinSet.isEmpty()) {
                list.add(pinyinSet);
            }
        }
        return list;
    }
}