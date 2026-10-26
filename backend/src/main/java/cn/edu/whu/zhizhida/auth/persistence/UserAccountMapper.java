package cn.edu.whu.zhizhida.auth.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {
    // V1 reports predate foreign keys. Keep only account-erasure support for old installations.
    @org.apache.ibatis.annotations.Delete("DELETE FROM career_reports WHERE user_id = #{userId}")
    int deleteLegacyReports(@org.apache.ibatis.annotations.Param("userId") Long userId);
}
