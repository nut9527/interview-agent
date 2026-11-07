// 读取本轮 Surefire 的实际测试类路径，允许 Maven 在命令行覆盖依赖版本。
export function testVersions(xml) {
  function property(name) {
    const prefix = `<property name="${name}" value="`;
    const start = xml.indexOf(prefix);
    if (start < 0) throw new Error(`Missing Surefire property: ${name}`);
    const offset = start + prefix.length;
    return xml.slice(offset, xml.indexOf('"', offset));
  }
  const paths = property('java.class.path').replaceAll('\\', '/').split(property('path.separator'));
  function version(artifact) {
    const versions = paths.flatMap(path => {
      const [directory, version, jar] = path.split('/').slice(-3);
      return directory === artifact && jar === `${artifact}-${version}.jar` ? [version] : [];
    });
    if (versions.length !== 1) throw new Error(`Expected one resolved JAR for ${artifact}`);
    return versions[0];
  }
  return { java: property('java.specification.version'), springBoot: version('spring-boot'),
    langchain4j: version('langchain4j-open-ai'), jackson3Module: version('langchain4j-core-jackson3') };
}
