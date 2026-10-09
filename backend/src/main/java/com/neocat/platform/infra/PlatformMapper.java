package com.neocat.platform.infra;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import com.neocat.platform.infra.row.ChannelRow;
import com.neocat.platform.infra.row.PlatformRow;

/**
 * 平台档案与通道配置 Mapper（表 {@code nc_platform_profile} / {@code nc_channel_config}）。
 */
@Mapper
public interface PlatformMapper {

    /** 单行表（id = 1）。 */
    PlatformRow selectProfile();

    void updateProfile(PlatformRow row);

    List<ChannelRow> selectChannels();

    void upsertChannel(ChannelRow row);
}
