package com.github.oilvegetable.pun_generator.service;

import java.util.List;
import java.util.Map;

public interface PunService {
    // 改为 List
    Map<String, List<String>> generatePun(String inputWord, List<String> targetTypes);

    // 新增：获取分类菜单
    Map<String, List<String>> getCategoryMap();
}