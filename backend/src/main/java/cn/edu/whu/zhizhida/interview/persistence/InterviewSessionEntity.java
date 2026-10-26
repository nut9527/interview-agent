package cn.edu.whu.zhizhida.interview.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("interview_sessions")
public class InterviewSessionEntity {

    @TableId(type = IdType.INPUT)
    private String sessionId;
    private Long userId;
    private String targetRole;
    private Long resumeId;
    private String resumeSnapshot;
    private Long resumeRevision;
    private Long jobId;
    private String jobSnapshot;
    private String mode;
    private String difficulty;
    private Integer totalRounds;
    private String state;
    private Integer turnCount;
    private String turnsJson;
    private String preparationJson;
    private String agentJson;
    private Integer answeredCount;
    private String preview;
    private String answerLeaseToken;
    private LocalDateTime answerLeaseUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
