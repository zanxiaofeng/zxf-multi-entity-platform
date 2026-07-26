package com.zxf.platform.core.infrastructure.observation;

import com.zxf.platform.core.context.PlatformProperties;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * 运行期漂移检测（文档 6.3 第 3 条防线）：{@code /actuator/info} 输出当前实体，
 * 接入监控巡检，发现"实体 A 的命名空间里跑着实体 B 的镜像"立即告警。
 */
@Component
public class EntityInfoContributor implements InfoContributor {

    private final PlatformProperties properties;

    public EntityInfoContributor(PlatformProperties properties) {
        this.properties = properties;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("entity", properties.entity());
    }
}
