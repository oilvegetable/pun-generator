package com.github.oilvegetable.pun_generator.service.impl;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.text.StrSplitter;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.oilvegetable.pun_generator.service.PunService;
import net.sourceforge.pinyin4j.PinyinHelper;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PunServiceImpl implements PunService {

    public static final String GROUP_XINHUA = "新华字典数据库";
    public static final String GROUP_THUOCL = "THUOCL中文词库";

    private final Map<String, List<String>> categoryMap = new LinkedHashMap<>();
    private final Map<String, Set<DictItem>> invertedIndex = new HashMap<>();
    private final Set<String> allTypes = new HashSet<>();

    private static class DictItem {
        String text;    // 用于匹配的文本 (歇后语仅存answer)
        String type;
        String extra;   // 歇后语存riddle
        int frequency;
        List<String> pinyins;

        public DictItem(String text, String type, String extra, int frequency, List<String> pinyins) {
            this.text = text;
            this.type = type;
            this.extra = extra;
            this.frequency = frequency;
            this.pinyins = pinyins;
        }
    }

    private static class MatchResult {
        String finalPun; // 仅仅是匹配部分的梗 (不含歇后语前半句)
        int score;
        public MatchResult(String finalPun, int score) {
            this.finalPun = finalPun;
            this.score = score;
        }
    }

    private static class MergedResult {
        String finalPun;    // 完整的梗 (含高亮标签)
        int maxScore;
        int maxFrequency;
        String type;
        Set<String> originTexts = new LinkedHashSet<>();

        public MergedResult(String finalPun, int score, int frequency, String originText, String type) {
            this.finalPun = finalPun;
            this.maxScore = score;
            this.maxFrequency = frequency;
            this.type = type;
            this.originTexts.add(originText);
        }

        public void addOrigin(String originText, int score, int frequency) {
            this.originTexts.add(originText);
            this.maxScore = Math.max(this.maxScore, score);
            this.maxFrequency = Math.max(this.maxFrequency, frequency);
        }

        public String getDisplayString() {
            // 直接返回构造好的带标签的字符串，配合原词
            // 前端需要用 v-html 解析
            return finalPun + " (原词：" + String.join("、", originTexts) + ")";
        }
    }

    @PostConstruct
    public void initData() {
        System.out.println("正在构建全量倒排索引...");
        categoryMap.put(GROUP_XINHUA, new ArrayList<>());
        categoryMap.put(GROUP_THUOCL, new ArrayList<>());

        // 加载新华字典
        loadJsonData("idiom.json", "成语俗语", "word", "explanation", 500, GROUP_XINHUA);
        loadJsonData("xiehouyu.json", "歇后语", "answer", "riddle", 100, GROUP_XINHUA);
        loadJsonData("ci.json", "词语", "ci", "explanation", 100, GROUP_XINHUA);

        // 加载 THUOCL
        loadThuoclData("thuocl/THUOCL_animal.txt", "动物", GROUP_THUOCL);
        loadThuoclData("thuocl/THUOCL_caijing.txt", "财经", GROUP_THUOCL);
        loadThuoclData("thuocl/THUOCL_car.txt", "汽车", GROUP_THUOCL);
        loadThuoclData("thuocl/THUOCL_chengyu.txt", "成语(THU)", GROUP_THUOCL);
        loadThuoclData("thuocl/THUOCL_diming.txt", "地名", GROUP_THUOCL);
        loadThuoclData("thuocl/THUOCL_food.txt", "饮食", GROUP_THUOCL);
        loadThuoclData("thuocl/THUOCL_it.txt", "IT互联网", GROUP_THUOCL);
        loadThuoclData("thuocl/THUOCL_law.txt", "法律", GROUP_THUOCL);
        loadThuoclData("thuocl/THUOCL_lishimingren.txt", "历史名人", GROUP_THUOCL);
        loadThuoclData("thuocl/THUOCL_medical.txt", "医学", GROUP_THUOCL);
        loadThuoclData("thuocl/THUOCL_poem.txt", "诗歌", GROUP_THUOCL);

        System.out.println("索引构建完毕");
    }

    @Override
    public Map<String, List<String>> getCategoryMap() {
        return categoryMap;
    }

    @Override
    public Map<String, List<String>> generatePun(String inputWord, List<String> targetTypes) {
        Map<String, List<String>> resultMap = new LinkedHashMap<>();
        List<String> searchTypes = (targetTypes == null || targetTypes.isEmpty()) ? new ArrayList<>(allTypes) : targetTypes;

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
                    if (resultMap.containsKey(item.type)) {
                        candidates.add(item);
                    }
                }
            }
        }

        Map<String, Map<String, MergedResult>> tempGroupedMap = new HashMap<>();
        for (String type : searchTypes) {
            tempGroupedMap.put(type, new HashMap<>());
        }

        // 2. 细算
        for (DictItem item : candidates) {
            MatchResult match = calculateBestMatch(inputWord, inputPinyins, item);
            if (match != null) {
                Map<String, MergedResult> group = tempGroupedMap.get(item.type);
                if (group != null) {

                    // 构造完整的展示字符串
                    String fullPunDisplay = match.finalPun;
                    String fullOriginText = item.text;

                    // 如果是歇后语，把前半句(riddle)拼回去
                    if ("歇后语".equals(item.type) && StrUtil.isNotBlank(item.extra)) {
                        // 格式：猪八戒照镜子——里外不<span...>茉莉</span>
                        fullPunDisplay = item.extra + "——" + match.finalPun;
                        fullOriginText = item.extra + "——" + item.text;
                    }

                    if (group.containsKey(fullPunDisplay)) {
                        group.get(fullPunDisplay).addOrigin(fullOriginText, match.score, item.frequency);
                    } else {
                        group.put(fullPunDisplay, new MergedResult(fullPunDisplay, match.score, item.frequency, fullOriginText, item.type));
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
                if (Math.abs(r1.maxScore - r2.maxScore) > 15) return r2.maxScore - r1.maxScore;
                return r2.maxFrequency - r1.maxFrequency;
            });
            List<String> sortedStrings = list.stream().limit(20).map(MergedResult::getDisplayString).collect(Collectors.toList());
            resultMap.put(type, sortedStrings);
        }
        return resultMap;
    }

    private void loadJsonData(String fileName, String type, String keyField, String extraField, int defaultFreq, String groupName) {
        try {
            allTypes.add(type);
            categoryMap.get(groupName).add(type);
            String jsonStr = ResourceUtil.readUtf8Str(fileName);
            if (jsonStr == null) return;
            JSONArray array = JSONUtil.parseArray(jsonStr);
            for (Object obj : array) {
                JSONObject json = (JSONObject) obj;

                String text = "";
                String extra = json.getStr(extraField);

                // 【核心修改】歇后语：text只存answer，extra存riddle
                if ("歇后语".equals(type)) {
                    String riddle = json.getStr("riddle");
                    String answer = json.getStr("answer");
                    if (StrUtil.isBlank(riddle) || StrUtil.isBlank(answer)) continue;
                    text = answer; // 只匹配后半句
                    extra = riddle; // 存前半句
                } else {
                    text = json.getStr(keyField);
                }

                if (text == null || text.trim().isEmpty()) continue;
                List<String> pinyins = getStringPinyins(text);
                if (pinyins.isEmpty()) continue;
                DictItem item = new DictItem(text, type, extra, defaultFreq, pinyins);
                for (String py : pinyins) {
                    invertedIndex.computeIfAbsent(py, k -> new HashSet<>()).add(item);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void loadThuoclData(String filePath, String type, String groupName) {
        try {
            allTypes.add(type);
            categoryMap.get(groupName).add(type);
            String content = ResourceUtil.readUtf8Str(filePath);
            if (content == null) return;
            List<String> lines = StrSplitter.split(content, '\n', true, true);
            for (String line : lines) {
                List<String> parts = StrSplitter.split(line, "\t", true, true);
                if (parts.size() < 2) continue;
                String text = parts.get(0);
                String freqStr = parts.get(1);
                if (text.length() < 2) continue;
                int frequency = 0;
                try { frequency = Integer.parseInt(freqStr); } catch (NumberFormatException e) { continue; }
                List<String> pinyins = getStringPinyins(text);
                if (pinyins.isEmpty()) continue;
                DictItem item = new DictItem(text, type, "", frequency, pinyins);
                for (String py : pinyins) {
                    invertedIndex.computeIfAbsent(py, k -> new HashSet<>()).add(item);
                }
            }
        } catch (Exception e) {}
    }

    private MatchResult calculateBestMatch(String inputWord, List<String> inputPinyins, DictItem item) {
        List<String> idiomPinyins = item.pinyins;
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
                        foundAt = m;
                        break;
                    }
                }
                if (foundAt != -1) {
                    currentIndices.add(foundAt);
                    currentScore += 10;
                    int lastIdx = currentIndices.get(currentIndices.size() - 2);
                    if (foundAt == lastIdx + 1) currentScore += 20;
                    currentIdiomSearchIdx = foundAt + 1;
                } else {
                    possible = false;
                    break;
                }
            }
            if (possible) {
                if (currentScore > bestScore) {
                    bestScore = currentScore;
                    bestIndices = new ArrayList<>(currentIndices);
                }
            }
        }
        if (bestIndices == null) return null;
        String finalPun = constructPunString(inputWord, item.text, bestIndices);
        return new MatchResult(finalPun, bestScore);
    }

    /**
     * 构造高亮字符串
     * 返回格式示例：黄发<span class="text-red-500 font-bold">台</span><span class="text-red-500 font-bold">北</span>
     */
    private String constructPunString(String inputWord, String itemText, List<Integer> indices) {
        StringBuilder sb = new StringBuilder();
        Set<Integer> replaceSet = new HashSet<>(indices);
        Map<Integer, Character> indexToInputChar = new HashMap<>();
        for (int k = 0; k < indices.size(); k++) {
            if (k < inputWord.length()) {
                indexToInputChar.put(indices.get(k), inputWord.charAt(k));
            }
        }

        // 定义高亮样式的开始和结束标签
        // 使用 text-red-500 (Tailwind红色) 和 font-bold (加粗)
        String highlightStart = "<span class=\"text-red-500 font-bold\">";
        String highlightEnd = "</span>";

        for (int i = 0; i < itemText.length(); i++) {
            if (replaceSet.contains(i)) {
                // 如果是替换字，包裹标签
                sb.append(highlightStart);
                sb.append(indexToInputChar.get(i));
                sb.append(highlightEnd);
            } else {
                sb.append(itemText.charAt(i));
            }
        }
        return sb.toString();
    }

    private List<String> getStringPinyins(String str) {
        List<String> list = new ArrayList<>();
        for (char c : str.toCharArray()) {
            String py = getCharPinyin(c);
            if (py != null) list.add(py);
        }
        return list;
    }

    private String getCharPinyin(char c) {
        try {
            String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(c);
            if (pinyins != null && pinyins.length > 0) {
                return pinyins[0].replaceAll("\\d", "");
            }
        } catch (Exception e) {}
        return null;
    }
}