package com.github.oilvegetable.pun_generator.controller;

import com.github.oilvegetable.pun_generator.service.PunService;
import com.github.oilvegetable.pun_generator.vo.PunResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/pun")
public class PunController {

    @Autowired
    private PunService punService;

    // 获取分类菜单
    @GetMapping("/categories")
    public Map<String, List<String>> getCategories() {
        return punService.getCategoryMap();
    }

    @GetMapping("/types-ordered")
    public List<String> getTypesOrdered() {
        return punService.getAllTypesOrdered();
    }

    @GetMapping("/types-default")
    public List<String> getDefaultTypes() {
        return punService.getDefaultSelectedTypes();
    }

    // 生成
    @GetMapping("/generate")
    public Map<String, List<PunResult>> generate(
            @RequestParam String word,
            // 接收 List 保证顺序
            @RequestParam(required = false) List<String> types,
            @RequestParam(required = false, defaultValue = "true") Boolean ignoreOrder) {
        return punService.generatePun(word, types, ignoreOrder);
    }
}