package com.wgh.rag.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.eval.entity.EvalCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EvalCaseMapper extends BaseMapper<EvalCase> {

    /**
     * 读取某版本的全部用例快照（版本内不可变，故不带排序以外的过滤条件）
     */
    @Select("SELECT * FROM wgh_eval_case WHERE dataset_id = #{datasetId} AND dataset_version = #{version}"
            + " AND deleted = 0 ORDER BY id")
    List<EvalCase> selectByVersion(@Param("datasetId") Long datasetId, @Param("version") Integer version);

    /**
     * 数据集已有版本号（升序），用于版本下拉与对比
     */
    @Select("SELECT DISTINCT dataset_version FROM wgh_eval_case WHERE dataset_id = #{datasetId}"
            + " AND deleted = 0 ORDER BY dataset_version")
    List<Integer> selectVersions(@Param("datasetId") Long datasetId);
}
