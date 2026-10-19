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
package io.github.nephets.pgvector.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四个枚举的自身契约：{@code PgVectorTypeTest} 钉住具体值，这里守<strong>不变量</strong>—— 两个算子不许共用
 * opclass、两个索引类型不许共用关键字。
 *
 * <p>为什么值得单测：opclass 撞车意味着「换算子不换索引」能被静默放过，关键字撞车意味着 DDL 会指错索引类型。
 */
@DisplayName("枚举不变量")
class EnumsContractTest {

    @Nested
    @DisplayName("DistanceOperator")
    class Operator {

        @Test
        @DisplayName("恰好三个算子，一个不多一个不少")
        void hasExactlyThree() {
            assertThat(DistanceOperator.values()).hasSize(3);
        }

        @Test
        @DisplayName("符号、opclass、语义说明两两不重复：任何一项撞车都会让「换算子」被静默放过")
        void noDuplicates() {
            assertThat(DistanceOperator.values())
                    .extracting(DistanceOperator::symbol)
                    .doesNotHaveDuplicates();
            assertThat(DistanceOperator.values())
                    .extracting(DistanceOperator::operatorClass)
                    .doesNotHaveDuplicates();
            assertThat(DistanceOperator.values())
                    .extracting(DistanceOperator::semantics)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("符号与语义都不为空：DDL 与错误消息都直接用它")
        void noBlankFields() {
            for (DistanceOperator operator : DistanceOperator.values()) {
                assertThat(operator.symbol()).isNotBlank();
                assertThat(operator.operatorClass()).isNotBlank();
                assertThat(operator.semantics()).isNotBlank();
            }
        }

        @Test
        @DisplayName("matchesOperatorClass(null) 返回 false 而不是 NPE：调用方拿不到 opclass 时不能炸")
        void nullOperatorClassIsNotAMatch() {
            for (DistanceOperator operator : DistanceOperator.values()) {
                assertThat(operator.matchesOperatorClass(null)).isFalse();
            }
        }

        @Test
        @DisplayName("valueOf round-trip")
        void valueOfRoundTrip() {
            for (DistanceOperator operator : DistanceOperator.values()) {
                assertThat(DistanceOperator.valueOf(operator.name())).isSameAs(operator);
            }
        }
    }

    @Nested
    @DisplayName("IndexType / JoinType / SortDirection")
    class OtherEnums {

        @Test
        @DisplayName("恰好两种索引类型，关键字就是 USING 后面那个")
        void indexTypes() {
            assertThat(IndexType.values()).hasSize(2);
            assertThat(IndexType.values())
                    .extracting(IndexType::keyword)
                    .containsExactlyInAnyOrder("hnsw", "ivfflat");
        }

        @Test
        @DisplayName("恰好三种 JOIN，关键字互不相同")
        void joinTypes() {
            assertThat(JoinType.values()).hasSize(3);
            assertThat(JoinType.values())
                    .extracting(JoinType::keyword)
                    .containsExactlyInAnyOrder("INNER", "LEFT", "RIGHT");
        }

        @Test
        @DisplayName("恰好两种排序方向")
        void sortDirections() {
            assertThat(SortDirection.values()).hasSize(2);
            assertThat(SortDirection.values())
                    .extracting(SortDirection::keyword)
                    .containsExactlyInAnyOrder("ASC", "DESC");
        }
    }
}
