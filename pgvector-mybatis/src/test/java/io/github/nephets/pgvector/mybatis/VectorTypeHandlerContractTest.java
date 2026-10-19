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

import io.github.nephets.pgvector.core.VectorFormatException;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PGobject;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;

/**
 * MyBatis 类型处理器的边角用例：{@code VectorTypeHandlerTest} 覆盖读写主路径， 这里盯住 {@code toPgObject}
 * 的公开契约——它被单独暴露出来正是为了无数据库测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MyBatis 类型处理器：PGobject 包装契约")
class VectorTypeHandlerContractTest {

    private static final float[] VECTOR = {0.1f, -0.2f, 0.3f};

    @Mock private PreparedStatement preparedStatement;

    @Test
    @DisplayName("toPgObject：type 钉死为 vector，value 是 pgvector 文本表示")
    void wrapsAsVectorPgObject() {
        PGobject pgObject = VectorTypeHandler.toPgObject(VECTOR);
        assertThat(pgObject.getType()).isEqualTo("vector");
        assertThat(pgObject.getValue()).isEqualTo("[0.1,-0.2,0.3]");
    }

    @Test
    @DisplayName("toPgObject(null)：明确报 VectorFormatException，而不是把 null 包成 PGobject")
    void rejectsNull() {
        assertThatThrownBy(() -> VectorTypeHandler.toPgObject(null))
                .isInstanceOf(VectorFormatException.class)
                .hasMessageContaining("不能为 null");
    }

    @Test
    @DisplayName("toPgObject 遇 NaN：拒绝，脏数据到不了驱动层")
    void rejectsNaN() {
        assertThatThrownBy(() -> VectorTypeHandler.toPgObject(new float[] {Float.NaN}))
                .isInstanceOf(VectorFormatException.class)
                .hasMessageContaining("NaN");
    }

    @Test
    @DisplayName("默认期望维度是 NO_DIMENSION_CHECK（-1）")
    void defaultsToNoDimensionCheck() {
        assertThat(new VectorTypeHandler().expectedDimensions())
                .isEqualTo(VectorTypeHandler.NO_DIMENSION_CHECK);
    }

    @Test
    @DisplayName("setNonNullParameter 忽略传入的 JdbcType：类型由 PGobject 决定，与调用方传什么无关")
    void ignoresJdbcTypeArgument() throws SQLException {
        new VectorTypeHandler().setNonNullParameter(preparedStatement, 2, VECTOR, JdbcType.VARCHAR);
        verify(preparedStatement).setObject(anyInt(), isA(PGobject.class));
    }
}
