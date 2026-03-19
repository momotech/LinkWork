package com.linkwork.service.skill;

import com.linkwork.agent.skill.core.SkillException;

public class SkillConflictException extends SkillException {
    public SkillConflictException(String message) {
        super(message);
    }
}
