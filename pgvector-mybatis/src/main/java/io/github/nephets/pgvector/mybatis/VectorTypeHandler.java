/*
 * Copyright 2026 The pgvector-kit Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nephets.pgvector.mybatis;

import io.github.nephets.pgvector.core.VectorCodec;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis 的 {@code vector} ↔ {@code float[]} 类型处理器。
 *
 * <p>MyBatis 内置的类型处理器里没有 {@code vector}，于是每个接 pgvector 的项目都要写一个。 这里两个地方值得说明：
 *
 * <ol>
 *   <li><strong>写参数用 {@link PGobject} 而不是 {@code setString}</strong>。 {@code setString} 会让 pgjdbc
 *       把参数标成 {@code varchar}，于是 {@code embedding <=> ?} 会报 {@code operator does not exist: vector
 *       <=> character varying}—— 插入能过、查询过不去，是个很容易在开发期漏掉的坑。 {@code PGobject("vector", ...)} 会让驱动用
 *       vector 的 OID 发送参数，插入和查询都对。
 *   <li><strong>可选维度校验</strong>。建表写的是 {@code vector(1536)}， 这里可以再写一次 1536；维度不符时在 Java
 *       侧抛错，而不是等数据库报错。
 * </ol>
 *
 * <p>用法（XML）：
 *
 * <pre>{@code
 * <result column="embedding" property="embedding"
 *         typeHandler="io.github.nephets.pgvector.mybatis.VectorTypeHandler"/>
 * }</pre>
 *
 * <p>需要维度校验时用构造器注入（MyBatis 支持 {@code javaType} 之外的构造器参数写法， 更简单的做法是注册一个子类）：
 *
 * <pre>{@code
 * public class Embedding1536Handler extends VectorTypeHandler {
 *   public Embedding1536Handler() { super(1536); }
 * }
 * }</pre>
 */
public class VectorTypeHandler extends BaseTypeHandler<float[]> {

    private static final Logger log = LoggerFactory.getLogger(VectorTypeHandler.class);

    /** -1 表示不校验维度。 */
    public static final int NO_DIMENSION_CHECK = -1;

    private final int expectedDimensions;

    public VectorTypeHandler() {
        this(NO_DIMENSION_CHECK);
    }

    /**
     * @param expectedDimensions 期望维度；传 {@link #NO_DIMENSION_CHECK} 表示不校验
     */
    public VectorTypeHandler(int expectedDimensions) {
        if (expectedDimensions != NO_DIMENSION_CHECK && expectedDimensions <= 0) {
            throw new IllegalArgumentException(
                    "期望维度必须为正数，或者传 NO_DIMENSION_CHECK("
                            + NO_DIMENSION_CHECK
                            + ") 表示不校验；实际为 "
                            + expectedDimensions);
        }
        this.expectedDimensions = expectedDimensions;
    }

    /** 期望维度；{@link #NO_DIMENSION_CHECK} 表示不校验。 */
    public int expectedDimensions() {
        return expectedDimensions;
    }

    /**
     * 把 {@code float[]} 包成 pgjdbc 认识的 {@code vector} 对象。
     *
     * <p>单独暴露出来是为了让它在没有数据库连接的情况下也能被测试（见 VectorTypeHandlerTest）。
     */
    public static PGobject toPgObject(float[] values) {
        PGobject pgObject = new PGobject();
        pgObject.setType("vector");
        try {
            pgObject.setValue(VectorCodec.toLiteral(values));
        } catch (SQLException e) {
            // PGobject#setValue 声明了这个受检异常，实际上对 vector 字面量永远不会抛
            log.error("failed to set vector literal into PGobject", e);
            throw new IllegalStateException("写入 PGobject 失败", e);
        }
        return pgObject;
    }

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int i, float[] parameter, JdbcType jdbcType) throws SQLException {
        log.debug("setNonNullParameter index={} dims={}", i, parameter.length);
        if (expectedDimensions != NO_DIMENSION_CHECK) {
            VectorCodec.requireDimensions(parameter, expectedDimensions, "MyBatis vector 参数");
        }
        ps.setObject(i, toPgObject(parameter));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private float[] parse(String literal) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        log.debug("parse vector from column literalChars={}", literal.length());
        float[] values = VectorCodec.fromLiteral(literal);
        if (expectedDimensions != NO_DIMENSION_CHECK) {
            VectorCodec.requireDimensions(values, expectedDimensions, "MyBatis vector 结果");
        }
        return values;
    }
}
