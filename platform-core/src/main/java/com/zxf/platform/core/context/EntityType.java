package com.zxf.platform.core.context;

/**
 * 实体标识。
 *
 * <p>注意：实体枚举放在核心层意味着每加一个实体都要改核心层。实体数预期会增长时，
 * 注册表键可直接用 {@code String}（{@code platform.entity} 的值），新增实体零改动核心层。
 * 两个实体的阶段枚举更简单，且编译期可穷举——按需选择（文档 5.2.1）。
 */
public enum EntityType {
    ALPHA,
    BETA
}
