package com.github.oilvegetable.pun_generator.service;

import com.github.oilvegetable.pun_generator.vo.PunResult;

import java.util.List;
import java.util.Map;

public interface PunService {

    void initData();

    List<String> getAllTypesOrdered();

    List<String> getDefaultSelectedTypes();

    Map<String, List<PunResult>> generatePun(String inputWord, List<String> targetTypes, boolean ignoreOrder);

    Map<String, List<String>> getCategoryMap();
}