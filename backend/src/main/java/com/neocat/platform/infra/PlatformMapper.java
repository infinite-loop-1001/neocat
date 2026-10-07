package com.neocat.platform.infra;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 平台档案与通道配置 Mapper（表 {@code nc_platform_profile} / {@code nc_channel_config}）。
 */
@Mapper
public interface PlatformMapper {

    /** 单行表（id = 1）。 */
    PlatformRepositoryAdapter.PlatformRow selectProfile();

    int updateProfile(PlatformRepositoryAdapter.PlatformRow row);

    List<PlatformRepositoryAdapter.ChannelRow> selectChannels();

    int upsertChannel(PlatformRepositoryAdapter.ChannelRow row);
}
