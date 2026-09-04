package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * 根 pom Enforcer 规则的自证 IT（评审修复 🔴2 / 双 profile 拦截）。
 *
 * <p><b>背景</b>：bannedDependencies 的 exclude 段序为
 * {@code groupId[:artifactId][:version][:type][:scope][:classifier]}——4 段写法
 * （{@code org.flowable:*:*:[7.0.0,8.0.0)}）把版本区间错落进 type 段，无构件 type
 * 等于该字符串，规则沦为<b>永不触发的死规则</b>，且构建期无任何征兆。
 *
 * <p><b>两层防线</b>：
 * <ol>
 *   <li>静态断言：DOM 解析根 pom，exclude 文本必须是 3 段写法——手滑回 4 段时本测试红；</li>
 *   <li>行为自证：临时最小 pom 携带同款规则 + 落在禁区区间的构件，子 Maven 构建必须
 *       FAILURE 且输出含规则 message——证明规则真的会拦，而非仅语法正确。</li>
 * </ol>
 * 双 profile 拦截（evaluateBeanshell）同样两层：静态断言 condition 存在于 app pom +
 * 双 marker 置 true 的临时 pom 构建必须红；另设 8.0.0 对照组防规则误伤合法版本。
 */
class EnforcerRulesSelfTest {

    /** 与根 pom enforce-flowable-baseline 保持一致的 3 段写法（version 段承载区间）。 */
    private static final String EXPECTED_FLOWABLE_EXCLUDE = "org.flowable:*:[7.0.0,8.0.0)";

    private static final String FLOWABLE_BAN_MESSAGE = "Spring Boot 4 必须使用 Flowable";

    private static final String DUAL_PROFILE_MESSAGE = "禁止同时激活 -Palpha -Pbeta";

    /** surefire 工作目录为模块 basedir（app/），根 pom 在上一级。 */
    private static Path rootPom() {
        return Path.of(System.getProperty("basedir", ".")).resolve("..").resolve("pom.xml").normalize();
    }

    private static Path appPom() {
        return Path.of(System.getProperty("basedir", "."), "pom.xml").normalize();
    }

    @Test
    void 根pom的Flowable排除规则必须是三段写法() throws Exception {
        var exclude = firstElementTextUnderExecution(rootPom(), "enforce-flowable-baseline", "exclude");
        assertThat(exclude)
                .as("exclude 必须为 3 段写法（版本区间落 version 段）；4 段写法把区间错落进 "
                        + "type 段，规则永不触发（评审发现的历史缺陷）")
                .isEqualTo(EXPECTED_FLOWABLE_EXCLUDE);
    }

    @Test
    void 注入Flowable7x依赖时Enforcer必须拒绝构建(@TempDir Path tempDir) throws Exception {
        var pom = writePom(tempDir, pomWithFlowableDependency("7.1.0"));
        var result = runMvnValidate(pom);

        assertThat(result.exitCode()).as("注入 7.1.0（落在 [7.0.0,8.0.0) 区间）时构建必须红").isNotZero();
        assertThat(result.output()).contains(FLOWABLE_BAN_MESSAGE);
    }

    @Test
    void 注入Flowable8x依赖时同规则构建通过(@TempDir Path tempDir) throws Exception {
        // 对照组：规则只拦 7.x 区间，不得误伤基线 8.0.0（防止"修复"变成一刀切）
        var pom = writePom(tempDir, pomWithFlowableDependency("8.0.0"));
        var result = runMvnValidate(pom);

        assertThat(result.exitCode()).as("8.0.0 是当前基线，必须放行").isZero();
    }

    @Test
    void appPom必须声明双实体profile拦截规则() throws Exception {
        var condition = firstElementTextUnderExecution(appPom(), "forbid-dual-entity-profiles", "condition");
        assertThat(condition)
                .as("forbid-dual-entity-profiles 的 beanshell condition 必须同时检测 alpha/beta 两个 marker")
                .contains("assembly.profile.alpha")
                .contains("assembly.profile.beta");
    }

    @Test
    void 双实体profile同开时Enforcer必须拒绝构建(@TempDir Path tempDir) throws Exception {
        // 两个 marker 均显式置 true，等价于 -Palpha -Pbeta 同开后的属性状态
        var pom = writePom(tempDir, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.zxf.platform.it</groupId>
                  <artifactId>dual-profile-it</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <assembly.profile.alpha>true</assembly.profile.alpha>
                    <assembly.profile.beta>true</assembly.profile.beta>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-enforcer-plugin</artifactId>
                        <version>3.6.1</version>
                        <executions>
                          <execution>
                            <id>forbid-dual-entity-profiles</id>
                            <goals><goal>enforce</goal></goals>
                            <configuration>
                              <rules>
                                <evaluateBeanshell>
                                  <condition>!("true".equals("${assembly.profile.alpha}") &amp;&amp; "true".equals("${assembly.profile.beta}"))</condition>
                                  <message>%s</message>
                                </evaluateBeanshell>
                              </rules>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(DUAL_PROFILE_MESSAGE));
        var result = runMvnValidate(pom);

        assertThat(result.exitCode()).as("-Palpha -Pbeta 同开时构建必须红").isNotZero();
        assertThat(result.output()).contains(DUAL_PROFILE_MESSAGE);
    }

    /** 最小 IT pom：携带与根 pom 同款的 Flowable 版本禁用规则 + 指定版本的 flowable-engine（type=pom 免下载整棵依赖树）。 */
    private static String pomWithFlowableDependency(String version) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.zxf.platform.it</groupId>
                  <artifactId>flowable-ban-it</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <dependencies>
                    <dependency>
                      <groupId>org.flowable</groupId>
                      <artifactId>flowable-engine</artifactId>
                      <version>%s</version>
                      <type>pom</type>
                    </dependency>
                  </dependencies>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-enforcer-plugin</artifactId>
                        <version>3.6.1</version>
                        <executions>
                          <execution>
                            <id>enforce-flowable-baseline</id>
                            <goals><goal>enforce</goal></goals>
                            <configuration>
                              <rules>
                                <bannedDependencies>
                                  <excludes>
                                    <exclude>%s</exclude>
                                  </excludes>
                                  <message>%s（7.x 基于 Spring Framework 6 / Boot 3，与 SB4 不兼容）</message>
                                </bannedDependencies>
                              </rules>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(version, EXPECTED_FLOWABLE_EXCLUDE, FLOWABLE_BAN_MESSAGE);
    }

    private static Path writePom(Path dir, String content) throws IOException {
        var pom = dir.resolve("pom.xml");
        Files.writeString(pom, content);
        return pom;
    }

    /** 子进程 {@code mvn validate} 的结果（exitCode + 捕获的标准输出）。 */
    private record MavenRun(int exitCode, String output) {
    }

    /** 子进程执行 {@code mvn validate}（本机 PATH 上的 mvn，与 CI 一致）。 */
    @SuppressWarnings("deprecation") // maven-invoker 3.3.0 仅提供 setGoals(List)（String... 是更高版本签名）
    private static MavenRun runMvnValidate(Path pom) throws MavenInvocationException {
        var output = new StringBuilder();
        var invoker = new DefaultInvoker();
        invoker.setOutputHandler(line -> output.append(line).append(System.lineSeparator()));
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pom.toFile());
        request.setGoals(List.of("validate"));
        request.setBatchMode(true);
        var result = invoker.execute(request);
        return new MavenRun(result.getExitCode(), output.toString());
    }

    /** DOM 解析 pom，定位指定 execution 下的目标元素文本（namespace 无关：按本地名匹配）。 */
    private static String firstElementTextUnderExecution(Path pom, String executionId, String targetTag)
            throws Exception {
        Document document = parse(pom);
        var executions = document.getElementsByTagName("execution");
        for (int i = 0; i < executions.getLength(); i++) {
            if (executions.item(i) instanceof Element execution
                    && executionId.equals(childText(execution, "id"))) {
                var targets = execution.getElementsByTagName(targetTag);
                assertThat(targets.getLength())
                        .as("execution id=%s 下应存在 <%s> 元素", executionId, targetTag)
                        .isPositive();
                return targets.item(0).getTextContent().trim();
            }
        }
        throw new AssertionError("pom 中未找到 execution id=" + executionId);
    }

    private static Document parse(Path pom) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        // 安全基线：pom 属仓库内受信文件，仍禁 DTD/外部实体防注入面
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(pom.toFile());
    }

    private static String childText(Element parent, String tag) throws DOMException {
        var children = parent.getElementsByTagName(tag);
        return children.getLength() > 0 ? children.item(0).getTextContent().trim() : "";
    }
}
