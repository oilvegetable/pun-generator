package com.github.oilvegetable.pun_generator.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PunResult {
    private String pun;             // 最终生成的梗
    private Set<String> origins;    // 原词列表
    private List<Integer> highlights; // 高亮索引
}