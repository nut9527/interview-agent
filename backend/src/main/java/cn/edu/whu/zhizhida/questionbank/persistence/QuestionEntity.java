package cn.edu.whu.zhizhida.questionbank.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.Instant;

@Data
@TableName("question_bank")
public class QuestionEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String title;
    private String question;
    private String topic;
    private String competencyId;
    private String difficulty;
    private String tagsJson;
    private String keyPointsJson;
    private String referenceAnswer;
    private String commonMistakesJson;
    private String followUpsJson;
    private String sourceType;
    private String sourceTitle;
    private String sourceUrl;
    private Instant createdAt;
    private Instant updatedAt;
    private Integer version;
    private Boolean published;
}

