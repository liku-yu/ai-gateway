package com.gateway.masking;

import java.util.List;

/**
 * 脱敏识别策略（策略模式）。
 * 新增一类敏感信息 = 新增一个实现并注册为 Bean。
 */
public interface MaskingStrategy {

    /** 类型标识，如 PHONE / ID_CARD / EMAIL / BANK_CARD / API_KEY。 */
    String type();

    /** 识别所有命中区间，按 start 升序。 */
    List<Span> detect(String text);
}
