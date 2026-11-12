package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.domain.InterviewTransitions;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;

import tools.jackson.databind.JsonNode;

record RunInput(
        JsonNode payload,
        InterviewSession session,
        InterviewTransitions.PendingAnswer pending,
        boolean opening,
        PracticeMemoryAccess.Selection memorySelection) {}
