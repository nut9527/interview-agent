package cn.edu.whu.zhizhida.resume.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("resume_versions")
public class ResumeVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long resumeId;
    private Long userId;
    private Integer versionNumber;
    private String snapshotJson;
    private String changeType;
    private String changeNote;
    private LocalDateTime createdAt;
}
