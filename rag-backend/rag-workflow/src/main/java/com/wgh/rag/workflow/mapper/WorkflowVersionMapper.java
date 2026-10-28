package com.wgh.rag.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.workflow.entity.WorkflowVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowVersionMapper extends BaseMapper<WorkflowVersion> {
}
