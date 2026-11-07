package cn.edu.whu.zhizhida.auth.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("app_sessions")
public class SessionEntity {

    @TableId(type = IdType.INPUT)
    private String tokenHash;
    private Long userId;
    private LocalDateTime expiresAt;
}
