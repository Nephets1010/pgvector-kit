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

import io.github.nephets.pgvector.core.VectorDimensionMismatchException;
import io.github.nephets.pgvector.core.VectorFormatException;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MyBatis vector TypeHandler")
class VectorTypeHandlerTest {

    private static final float[] VECTOR = {0.1f, -0.2f, 0.3f};
    private static final String LITERAL = "[0.1,-0.2,0.3]";

    @Mock private PreparedStatement preparedStatement;
    @Mock private ResultSet resultSet;
    @Mock private CallableStatement callableStatement;

    @Nested
    @DisplayName("写参数")
    class Write {

        @Test
        @DisplayName("必须发一个 type=vector 的 PGobject，而不是 setString —— 否则查询时报 operator does not exist")
        void writesPgObjectTypedAsVector() throws SQLException {
            new VectorTypeHandler()
                    .setNonNullParameter(preparedStatement, 1, VECTOR, JdbcType.OTHER);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(preparedStatement).setObject(eq(1), captor.capture());
            Object bound = captor.getValue();
            assertThat(bound).isInstanceOf(PGobject.class);
            PGobject pgObject = (PGobject) bound;
            assertThat(pgObject.getType()).isEqualTo("vector");
            assertThat(pgObject.getValue()).isEqualTo(LITERAL);
        }

        @Test
        @DisplayName("默认不校验维度")
        void doesNotCheckDimensionsByDefault() throws SQLException {
            new VectorTypeHandler()
                    .setNonNullParameter(preparedStatement, 1, new float[] {1f}, JdbcType.OTHER);
            verify(preparedStatement).setObject(anyInt(), isA(PGobject.class));
        }

        @Test
        @DisplayName("声明维度后，维度不符在 Java 侧就失败")
        void checksDimensionsWhenDeclared() {
            assertThatThrownBy(
                            () ->
                                    new VectorTypeHandler(1536)
                                            .setNonNullParameter(
                                                    preparedStatement,
                                                    1,
                                                    new float[768],
                                                    JdbcType.OTHER))
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageContaining("期望 1536 维")
                    .hasMessageContaining("实际 768 维");
        }

        @Test
        @DisplayName("声明维度且一致时放行")
        void passesWhenDimensionsMatch() throws SQLException {
            new VectorTypeHandler(3)
                    .setNonNullParameter(preparedStatement, 1, VECTOR, JdbcType.OTHER);
            verify(preparedStatement).setObject(eq(1), isA(PGobject.class));
        }

        @Test
        @DisplayName("NaN 分量不会被写进数据库")
        void rejectsNaN() {
            assertThatThrownBy(
                            () ->
                                    new VectorTypeHandler()
                                            .setNonNullParameter(
                                                    preparedStatement,
                                                    1,
                                                    new float[] {Float.NaN},
                                                    JdbcType.OTHER))
                    .isInstanceOf(VectorFormatException.class);
        }
    }

    @Nested
    @DisplayName("读结果")
    class Read {

        @Test
        @DisplayName("按列名读")
        void readsByColumnName() throws SQLException {
            when(resultSet.getString("embedding")).thenReturn(LITERAL);
            assertThat(new VectorTypeHandler().getNullableResult(resultSet, "embedding"))
                    .containsExactly(VECTOR);
        }

        @Test
        @DisplayName("按列下标读")
        void readsByColumnIndex() throws SQLException {
            when(resultSet.getString(3)).thenReturn(LITERAL);
            assertThat(new VectorTypeHandler().getNullableResult(resultSet, 3))
                    .containsExactly(VECTOR);
        }

        @Test
        @DisplayName("存储过程用 CallableStatement 读")
        void readsFromCallableStatement() throws SQLException {
            when(callableStatement.getString(2)).thenReturn(LITERAL);
            assertThat(new VectorTypeHandler().getNullableResult(callableStatement, 2))
                    .containsExactly(VECTOR);
        }

        @Test
        @DisplayName("NULL 列返回 null，不抛错")
        void returnsNullForNullColumn() throws SQLException {
            when(resultSet.getString("embedding")).thenReturn(null);
            assertThat(new VectorTypeHandler().getNullableResult(resultSet, "embedding")).isNull();
        }

        @Test
        @DisplayName("读出来的维度与声明不符时也要报错（说明列类型被改过）")
        void checksDimensionsOnRead() throws SQLException {
            when(resultSet.getString("embedding")).thenReturn("[1.0,2.0]");
            assertThatThrownBy(
                            () ->
                                    new VectorTypeHandler(1536)
                                            .getNullableResult(resultSet, "embedding"))
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageContaining("MyBatis vector 结果");
        }

        @Test
        @DisplayName("读出来的文本不合法时报错")
        void rejectsMalformedLiteral() throws SQLException {
            when(resultSet.getString("embedding")).thenReturn("not-a-vector");
            assertThatThrownBy(
                            () -> new VectorTypeHandler().getNullableResult(resultSet, "embedding"))
                    .isInstanceOf(VectorFormatException.class);
        }
    }

    @Nested
    @DisplayName("构造器校验")
    class Construction {

        @Test
        @DisplayName("0 或负数维度是配置错误，直接拒")
        void rejectsNonPositiveDimensions() {
            assertThatThrownBy(() -> new VectorTypeHandler(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new VectorTypeHandler(-5))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("NO_DIMENSION_CHECK 是合法的「不校验」声明")
        void acceptsNoDimensionCheck() {
            assertThat(
                            new VectorTypeHandler(VectorTypeHandler.NO_DIMENSION_CHECK)
                                    .expectedDimensions())
                    .isEqualTo(-1);
        }
    }
}
