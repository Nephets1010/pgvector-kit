# pgvector-kit

![CI](https://github.com/Nephets1010/pgvector-kit/actions/workflows/ci.yml/badge.svg?branch=main)
![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)
![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk)

让业务表和向量表写进同一条 SQL、同一个事务的 Spring Boot + pgvector 数据层组件。

坐标 `io.github.nephets`，当前版本 `0.1.0-SNAPSHOT`；运行条件：Java 21 / Spring Boot 3.5.16 / PostgreSQL + pgvector。

## 真实输出样例

以下输出由 `pgvector-core` 实际打印，不需要数据库；`pgvector-sample` 的真库 demo 需要 Docker，由 CI 的 `pgvector-smoke` job 保证。

`HybridQuery` 把标量过滤、余弦近邻和业务表 JOIN 拼成一条 SQL，并按占位符顺序保留绑定参数：

```text
[4]
SELECT s.sku_code, s.stock, (c.embedding <=> ?::vector) AS distance
FROM sku_embedding c
INNER JOIN sku s ON s.id = c.sku_id
WHERE s.warehouse = ? AND s.status = ? AND s.stock > ?
ORDER BY c.embedding <=> ?::vector ASC
LIMIT 20
[4] 绑定参数 5 个：[[0.12,-0.07,0.31,0.88,0.02,-0.45,0.19,0.63], SH01, ON_SALE, 0, [0.12,-0.07,0.31,0.88,0.02,-0.45,0.19,0.63]]
```

`IndexAdvisor` 同时返回选择理由和可执行 DDL：

```text
[5] 建议 HNSW + vector_cosine_ops：目标召回 0.95 ≥ 0.95：IVFFlat 要把召回推到这个水平，probes 必须开到接近 lists，扫的簇已接近全表，省下的内存换不来延迟优势；HNSW 的召回-延迟曲线更平缓，高召回区间是它的主场
[5] CREATE INDEX idx_sku_embedding_cosine ON sku_embedding USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
```

## 能力边界

能做：

- `VectorCodec` 在 Java 侧完成 vector 编解码，并拒绝非法数值和维度不符。
- `HybridQuery` 把标量过滤、向量近邻和业务表 JOIN 合成一条参数化 SQL。
- `IndexAdvisor` 按数据量、维度与召回要求返回带理由的索引建议和 DDL。

不能做：

- 不生成 embedding，调用方需要提供自己的向量。
- 不抽象其他向量数据库，只面向 PostgreSQL + pgvector。
- 不提供文档切分、prompt 管理、重排序或 LLM 调用等 RAG 能力。
- 不连接数据库建表或建索引，DDL 由调用方审核后执行。
- 不在距离算子与索引 opclass 不匹配时静默退化，而是直接报错。

完整限制见[「不做什么」](#不做什么)。

## 5 分钟快速开始

### 1. 引入依赖

Spring Boot 项目引 starter，只需要编解码与 DSL 则引 core：

```xml
<dependency>
  <groupId>io.github.nephets</groupId>
  <artifactId>pgvector-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```xml
<dependency>
  <groupId>io.github.nephets</groupId>
  <artifactId>pgvector-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

`0.1.0-SNAPSHOT` 是开发版本，尚未发布到公共仓库。想直接跑起来看输出，按下面三步从源码编译并运行。

### 2. 拉取代码并编译 core

这一步不需要 PostgreSQL 或 Docker：

```bash
git clone https://github.com/Nephets1010/pgvector-kit.git
cd pgvector-kit
./mvnw -B -ntp -pl pgvector-core -am compile
```

### 3. 保存单文件示例

把下面代码保存为仓库根目录下的 `QuickStart.java`：

```java
import io.github.nephets.pgvector.core.DistanceOperator;
import io.github.nephets.pgvector.core.HybridQuery;
import io.github.nephets.pgvector.core.IndexAdvice;
import io.github.nephets.pgvector.core.IndexAdvisor;
import io.github.nephets.pgvector.core.IndexRequest;
import io.github.nephets.pgvector.core.IndexType;
import io.github.nephets.pgvector.core.JoinType;
import io.github.nephets.pgvector.core.VectorCodec;
import io.github.nephets.pgvector.core.VectorFormatException;
import io.github.nephets.pgvector.core.VectorIndex;

public class QuickStart {

    private static final int DIMS = 8;

    public static void main(String[] args) {
        float[] embedding = {0.12f, -0.07f, 0.31f, 0.88f, 0.02f, -0.45f, 0.19f, 0.63f};

        // 1) 编解码：float[] 与 pgvector 的 [1,2,3] 文本表示互转
        System.out.println("[1] " + VectorCodec.toLiteral(embedding));

        // 2) NaN / Infinity 当场拒掉，不让脏数据落到磁盘
        try {
            VectorCodec.toLiteral(new float[] {0.1f, Float.NaN, 0.3f});
        } catch (VectorFormatException e) {
            System.out.println("[2] " + e.getMessage());
        }

        // 3) 维度不符：在 Java 侧就报，不等数据库报错
        try {
            VectorCodec.requireDimensions(
                    new float[] {0.1f, 0.2f, 0.3f}, DIMS, "sku_embedding.embedding");
        } catch (RuntimeException e) {
            System.out.println("[3] " + e.getMessage());
        }

        // 4) 一条 SQL：标量过滤 + 余弦近邻 + 业务表 JOIN
        VectorIndex index =
                new VectorIndex(
                        "idx_sku_embedding_cosine",
                        "sku_embedding",
                        "embedding",
                        IndexType.HNSW,
                        DistanceOperator.COSINE,
                        DIMS);

        HybridQuery query =
                HybridQuery.builder()
                        .select("s.sku_code", "s.stock")
                        .from("sku_embedding c")
                        .join(JoinType.INNER, "sku s", "s.id = c.sku_id")
                        .where("s.warehouse = ?", "SH01")
                        .where("s.status = ?", "ON_SALE")
                        .where("s.stock > ?", 0)
                        .nearest("c.embedding", embedding, DistanceOperator.COSINE)
                        .withIndex(index)
                        .limit(20)
                        .build();
        System.out.println("[4]\n" + query.sql());
        System.out.println("[4] 绑定参数 " + query.params().size() + " 个：" + query.params());

        // 5) 索引建议：给选择理由 + 可直接执行的 DDL
        IndexAdvice advice =
                IndexAdvisor.advise(
                        new IndexRequest(
                                200_000L,
                                DIMS,
                                0.95,
                                DistanceOperator.COSINE,
                                "sku_embedding",
                                "embedding",
                                "idx_sku_embedding_cosine",
                                false));
        System.out.println("[5] " + advice.summary());
        System.out.println("[5] " + advice.ddl());
        System.out.println("[5] " + advice.searchSettings());
    }
}
```

示例使用 8 维向量，与 `pgvector-sample/src/main/resources/schema.sql` 的 `VECTOR(8)` 一致；真实项目请换成自己的 embedding 维度。

### 4. 运行示例

Linux / macOS：

```bash
java -Dstdout.encoding=UTF-8 -cp "pgvector-core/target/classes:$HOME/.m2/repository/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar" QuickStart.java
```

Windows 命令提示符：

```bat
java -Dstdout.encoding=UTF-8 -cp "pgvector-core\target\classes;%USERPROFILE%\.m2\repository\org\slf4j\slf4j-api\2.0.18\slf4j-api-2.0.18.jar" QuickStart.java
```

`slf4j-api` 版本由 Spring Boot 3.5.16 的依赖管理决定；如果本地不是 2.0.18，请把路径换成 `~/.m2` 或 `%USERPROFILE%\.m2` 中的实际版本。`-Dstdout.encoding=UTF-8` 是 Windows 上必需的一项：控制台默认是 GBK，不加的话 stdout 不是合法 UTF-8，中文在 UTF-8 终端或重定向后是乱码。示例不接日志实现，因此 stderr 会显示三行 SLF4J NOP 提示，这是正常现象；下面只列 stdout。

完整输出：

```text
[1] [0.12,-0.07,0.31,0.88,0.02,-0.45,0.19,0.63]
[2] vector 第 1 个分量是 NaN：pgvector 不接受 NaN / Infinity
[3] 向量维度不符（sku_embedding.embedding）：期望 8 维，实际 3 维。pgvector 的 vector(N) 维度是列类型的一部分，不会静默截断；请检查 embedding 模型的输出维度与建表时的 vector(N) 是否一致。
[4]
SELECT s.sku_code, s.stock, (c.embedding <=> ?::vector) AS distance
FROM sku_embedding c
INNER JOIN sku s ON s.id = c.sku_id
WHERE s.warehouse = ? AND s.status = ? AND s.stock > ?
ORDER BY c.embedding <=> ?::vector ASC
LIMIT 20
[4] 绑定参数 5 个：[[0.12,-0.07,0.31,0.88,0.02,-0.45,0.19,0.63], SH01, ON_SALE, 0, [0.12,-0.07,0.31,0.88,0.02,-0.45,0.19,0.63]]
[5] 建议 HNSW + vector_cosine_ops：目标召回 0.95 ≥ 0.95：IVFFlat 要把召回推到这个水平，probes 必须开到接近 lists，扫的簇已接近全表，省下的内存换不来延迟优势；HNSW 的召回-延迟曲线更平缓，高召回区间是它的主场
[5] CREATE INDEX idx_sku_embedding_cosine ON sku_embedding USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
[5] SET hnsw.ef_search = 100;
```

## 模块表

根 POM 的 `<modules>` 只有五个，没有别的：

| 模块目录 | artifactId | 职责 | 依赖谁 | 边界与约束 |
| --- | --- | --- | --- | --- |
| `pgvector-core` | `pgvector-core` | vector 编解码（`VectorCodec`）、混合查询 DSL（`HybridQuery`）、SQL 标识符白名单（`SqlIdentifier`）、距离算子（`DistanceOperator`）、索引声明与建议（`VectorIndex` / `IndexAdvisor`）、pgvector 硬上限常量（`PgVectorLimits`） | 零框架依赖，仅允许 `slf4j-api` 日志门面（compile）；`junit-jupiter` 与 `assertj-core` 均为 test scope | 只用 JDK + SLF4J 日志门面。`src/main` 不许出现 `org.springframework`，这条由 CI 的 `architecture-boundaries` job 用 `dependency:list -DincludeScope=compile` 机器校验；不做 IO、不管连接 |
| `pgvector-mybatis` | `pgvector-mybatis` | MyBatis 的 `vector` 类型处理器 `VectorTypeHandler extends BaseTypeHandler<float[]>`，让 `float[]` 直接当实体字段用 | `pgvector-core`；`mybatis` 与 `postgresql` 均为 provided | 只做框架桥接。写参数时把值包成 `PGobject("vector", ...)` 以保证 OID 正确（`setString` 会让 `<=> ?` 报 `operator does not exist`）；不生成 SQL，不碰事务 |
| `pgvector-jpa` | `pgvector-jpa` | JPA 的 `vector` 属性转换器 `VectorConverter implements AttributeConverter<float[], String>`，让实体里直接写 `float[]` 字段 | `pgvector-core`；`jakarta.persistence-api` 为 provided | 只做框架桥接。`@Converter(autoApply = false)`，必须显式 `@Convert`；列类型要自己写成 `vector(N)`，JPA 不会从 `float[]` 推断出来；不能在 JPQL 里写距离算子 |
| `pgvector-spring-boot-starter` | `pgvector-spring-boot-starter` | `PgVectorTemplate`（执行 `HybridQuery` 的入口）、`PgVectorProperties`（`pgvector.*` 配置）、`PgVectorAutoConfiguration`（自动装配） | `pgvector-core`、`spring-boot-starter-jdbc`、`spring-boot-autoconfigure` | 有 `DataSource` 且单候选时提供 `PgVectorTemplate` bean，此外什么都不做：不建表、不建索引、不扫 Mapper、不创建连接。`ConditionalOnClass` 用字符串形式写，缺依赖时安静跳过而不是 `NoClassDefFoundError` |
| `pgvector-sample` | `pgvector-sample` | 可运行 demo：库存业务表 + 向量表写进同一个事务，一条 SQL 查出相似商品；另有 `DeterministicEmbedding`（demo 用的确定性假向量）与 `PgVectorHybridQueryIT`（真库端到端测试） | `pgvector-spring-boot-starter`、`spring-boot-starter-jdbc`、`postgresql`（runtime） | 只做演示，不发布为公共 API。代码中直接 import 了 core 的 `HybridQuery` / `VectorCodec` / `VectorIndex` 等（经 starter 传递依赖）。覆盖率门禁刻意关闭（见下） |

## 它解决什么问题

pgvector 让 PostgreSQL 能存向量、能算距离，但 JDBC 驱动不为 `vector` 提供任何类型映射，ORM 也不认识它。
于是每个接 pgvector 的项目都要自己写三样东西：`float[]` 和 `[1,2,3]` 的互转、标量过滤与向量近邻的组合查询、索引类型和参数的选择。
这三段手写代码各有各的坑，而且出错时大多不报错：

- 编解码不用 `Float.toString`，精度悄悄少了几位；不拒绝 `NaN` / `Infinity`，脏数据一直写到磁盘；不校验维度，等到数据库报错才发现。
- 先按向量取 20 条、再在内存里按业务条件过滤，剩下的可能只有 3 条。分页忽多忽少，排序和事务边界也都不对。
- 索引建的时候用 `vector_l2_ops`，查询时写 `<=>`，SQL 能跑、结果也对，只是索引被静默跳过、全程全表扫描。

本仓把这些收进一个模块：`VectorCodec` 负责编解码与 fail-fast 校验，`HybridQuery` 负责把标量过滤、向量近邻、业务表 JOIN 拼成**一条** SQL，`IndexAdvisor` 负责按数据量、维度、召回要求给出带理由的索引建议和可直接执行的 DDL。
MyBatis 与 JPA 各配一个薄适配层，让实体里能直接写 `float[]` 字段。

`pgvector-sample` 里的 `InventoryScenario` 是这件事的完整演示：写业务行和写向量行在同一个事务里（维度写错则整批回滚，不会留下"商品建好了但没有向量"的脏数据），查询则由一条 SQL 完成仓库、状态、库存三个标量过滤加余弦近邻再加一次 JOIN。

## 职责边界

### 全仓只有一个 core 模块

就叫 `pgvector-core`，目录名与 artifactId 同名，它是家族 `<repo>-core` 约定的落点。
不存在第二个 core，也不存在别的"公共工具模块"。任何跨模块复用的东西，落点只有这一个。

### `pgvector-core` 的硬边界

- 零框架依赖，仅允许 `slf4j-api` 日志门面（compile）。`src/main` 里 grep `org.springframework` 的结果是 0；`junit-jupiter` 与 `assertj-core` 均为 test scope。
- 父 POM 刻意不在 `<dependencies>` 里声明通用框架依赖（哪怕只是 `spring-boot-starter-test`）：一旦声明，每个子模块都会背上它的传递依赖，"core 零框架依赖，仅允许 slf4j-api 日志门面"就无法再用机器判定。
- 这条边界的理由写在 core 的 pom 注释里：类型桥接与 SQL 生成是纯字符串/数组逻辑，挂任何框架都会把它锁死在那个框架的版本上。
- 后果是：编解码、DSL、索引建议必须住在 core，不许往 starter 或适配层挪。

### 两个适配层不重复实现编解码

`pgvector-core` 的 `VectorCodec` 是**唯一**的 vector 文本表示与 `float[]` 互转实现，含 `NaN` / `Infinity` 拒绝、0 维拒绝、16000 维上限校验，以及 `requireDimensions` 的维度强校验（刻意不截断也不补零）。

两个适配层都只是框架桥接加可选维度校验：

- `pgvector-jpa` 的 `VectorConverter`：`convertToDatabaseColumn` 调 `VectorCodec.toLiteral`，`convertToEntityAttribute` 调 `VectorCodec.fromLiteral`，中间夹一次可选的 `requireDimensions`。
- `pgvector-mybatis` 的 `VectorTypeHandler`：`setNonNullParameter` 走 `VectorCodec.toLiteral` 后包 `PGobject`，三个 `getNullableResult` 都走同一个私有 `parse` 方法里的 `VectorCodec.fromLiteral`，同样夹一次可选维度校验。

两者各有一个 `NO_DIMENSION_CHECK = -1` 常量和一个构造器校验。这是适配层各自持有的那只"期望维度"参数，不是两份重复实现——它们的值 semantics 相同但归属不同（JPA 是字段级、MyBatis 是 handler 实例级），删掉任一个都会让使用方没法在这个层上指定维度。

**新增第三个适配层（比如 jOOQ、R2DBC）时，唯一正确的做法是继续委托 `VectorCodec`**，再在本层持有自己的参数。谁要是复制一份解析代码进去，就是在给三个地方埋同一批 bug。

### 依赖方向单向

```
pgvector-sample  -> pgvector-spring-boot-starter -> pgvector-core
pgvector-mybatis -> pgvector-core
pgvector-jpa     -> pgvector-core
```

core 不反向依赖任何东西，也不依赖任何上层模块。
另外两点值得说明：starter **不**依赖 jpa 或 mybatis，用哪个适配层由使用方自己选，自动装配不会替你拉进来；sample 也不依赖 jpa / mybatis，它走的是 JdbcTemplate 那条路。

## 不做什么

不做的清单也是设计的一部分，这几条都有对应的源码依据：

- **不做 embedding / 向量生成。** `DeterministicEmbedding` 是 demo 用的假向量（字符 uni-gram + bi-gram 直方图投影到 N 维再 L2 归一化），它存在的理由只是让 demo 零依赖、零 API Key、可复现，不是模型。根 POM 的注释里写明了刻意不引入 Spring AI。真实项目请把这里换成你自己的 embedding 服务输出。
- **不做向量数据库抽象层。** 只面向 PostgreSQL + pgvector。带向量近邻的查询执行前会先读 `DatabaseMetaData` 的产品名，不是 PostgreSQL 就直接抛 `UnsupportedDatabaseException`（starter 用 H2 测的就是这条）。不做方言层，也不抽象到其他向量库。
- **不做 RAG 框架。** 没有文档切分、没有 prompt 管理、没有重排序、没有 LLM 调用。
- **不做 ORM。** `PgVectorTemplate` 不做实体映射、不做缓存、不管理关联，它的类注释里写得很直接：业务行的写入照旧用你的 MyBatis / JPA / JdbcTemplate，本组件只负责那条混合查询。自动装配也不在启动时执行任何 DDL。
- **不在 JPQL 里支持距离算子。** JPQL 没有 `<=>` 这种运算符，Hibernate 也无从翻译。`VectorConverter` 的注释里给了替代路径：带向量近邻的查询用 core 的 `HybridQuery` 生成 SQL，再用 `EntityManager#createNativeQuery` 取回。另外转换器把值以文本表示绑定为字符串，靠 PostgreSQL 的赋值转换走通 INSERT / UPDATE，所以它也不适合出现在 WHERE 的比较表达式里。
- **不支持带空格或中文的表名与列名。** `SqlIdentifier` 把标识符限定在"字母或下划线开头，后续是字母、数字、下划线与 $"的白名单里，允许 `schema.table` 和表别名，其余（含引号、分号、注释、括号）一律拒绝。这是刻意的取舍：宁可让用户改列名，也不在拼 SQL 的地方留一个注入面。
- **不做维度不符时的静默修正。** `requireDimensions` 不截断也不补零——那两种"自动修正"会让查询看起来正常、实际全错，而且要在几周后因为"召回怎么这么差"才被发现。
- **不为算子与索引 opclass 不匹配做静默退化。** 声明了 `withIndex(...)` 却在查询里用了另一个算子，直接抛 `OperatorIndexMismatchException`。
- **不支持 `halfvec` / `sparsevec`。** 只有 `float[]` 与 `vector`。
- **DSL 不覆盖聚合查询。** `HybridQuery.Builder` 的查询结构方法只有 `select` / `from` / `join` / `where` / `nearest` / `orderBy` / `limit` / `offset`（另有 `withIndex` / `distanceAlias` / `expectDimensions` 三个辅助方法），没有 `GROUP BY`、没有 `HAVING`、没有子查询和 `UNION`。取总数用 `PgVectorTemplate#count` 包一层子查询实现，那条外包装不参与 DSL 的校验。
- **索引建议不落地。** `IndexAdvisor#advise` 返回 `IndexAdvice`，里面的 `ddl` 是可以执行的 SQL 文本，但本组件不连接数据库执行它，也不会替你创建任何东西。

## 构建与测试

```bash
./mvnw -B -ntp clean verify
```

需要 JDK 21 与 Maven 3.9.9 以上，两者都由 `maven-enforcer-plugin` 在构建时校验。

覆盖率门禁（jacoco `check`，各模块 `pom.xml` 里的 `jacoco.line.minimum`）：

| 模块 | 行覆盖率下限 |
| --- | --- |
| `pgvector-core` | 85% |
| `pgvector-mybatis` | 80% |
| `pgvector-jpa` | 80% |
| `pgvector-spring-boot-starter` | 70% |
| `pgvector-sample` | 关闭（`jacoco.skip=true`） |

sample 关闭门禁是有意的：它的主要逻辑在 `PgVectorHybridQueryIT` 里，依赖 Docker，本机没有 Docker 时会被合理跳过，那时任何百分比阈值都会变成一个"因为没跑测试而失败"的假红。这条 IT 的正确性由 CI 上带 pgvector 服务容器的 `pgvector-smoke` job 保证。

CI 一共四个 job：`verify`（编译 + 测试 + 覆盖率门禁）、`spotless`（google-java-format AOSP + Apache-2.0 许可证头，绑的是 `check` 不是 `apply`）、`architecture-boundaries`（core 不许出现 Spring 依赖）、`pgvector-smoke`（真库上跑 sample 的 IT）。
