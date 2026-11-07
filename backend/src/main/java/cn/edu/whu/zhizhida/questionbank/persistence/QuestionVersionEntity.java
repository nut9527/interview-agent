package cn.edu.whu.zhizhida.questionbank.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("question_bank_versions")
public class QuestionVersionEntity {
    private String questionId;
    private Integer version;
    private String contentJson;
}
