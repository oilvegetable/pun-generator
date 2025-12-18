package com.github.oilvegetable.pun_generator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "pun")
public class PunProperties {

    // 用于定义全选时生成结果时的词库优先级顺序
    private List<String> searchOrder;

    // 默认勾选
    private List<String> defaultDictNames;

    // 词库分类
    private List<GroupConfig> groups;

    // 默认初始展示数量
    private int initialDisplaySize = 20;

    // 默认每次点击加载数量
    private int loadMoreStep = 40;

    // 最低匹配字符数
    private int minMatchCount = 2;

    @Data
    public static class GroupConfig {
        private String name;
        private List<DictConfig> dicts;
    }

    @Data
    public static class DictConfig {
        private String name;
        private String path;
        private LoaderType loaderType;
        private String keyField;
        private String extraField;
        private int defaultFreq;
    }

    public enum LoaderType {
        JSON_NORMAL,
        JSON_XIEHOUYU,
        THUOCL
    }
}