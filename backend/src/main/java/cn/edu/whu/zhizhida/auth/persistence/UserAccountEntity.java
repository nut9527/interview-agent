package cn.edu.whu.zhizhida.auth.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("app_users")
public class UserAccountEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String targetRole;
    private String skillTags;
    private String accountType;
    private LocalDateTime demoExpiresAt;
}
