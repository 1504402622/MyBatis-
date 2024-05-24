package cn.glfs.mybatis.builder.xml;

import cn.glfs.mybatis.builder.BaseBuilder;
import cn.glfs.mybatis.datasource.DataSourceFactory;
import cn.glfs.mybatis.io.Resources;
import cn.glfs.mybatis.mapping.BoundSql;
import cn.glfs.mybatis.mapping.Environment;
import cn.glfs.mybatis.mapping.MappedStatement;
import cn.glfs.mybatis.mapping.SqlCommandType;
import cn.glfs.mybatis.session.Configuration;
import cn.glfs.mybatis.transaction.TransactionFactory;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.xml.sax.InputSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.Reader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 找到总配置文件和mapper映射文件并注入到sql语句类中的工具类
 */
public class XMLConfigBuilder extends BaseBuilder {
    private Element root;


    /**
     * 构造函数,将对数据源的配置类xml的字符输入流解析成一个 Document 对象，并给root赋值为根结点
     * @param reader
     */
    public XMLConfigBuilder(Reader reader) {
        //1.调用父类初始化Configuration
        super(new Configuration());
        //2.dom4j处理xml,创建了一个 SAXReader 对象，用于读取 XML 文件。
        SAXReader saxReader = new SAXReader();
        try {
            Document document = saxReader.read(new InputSource(reader));
            root = document.getRootElement();
        }catch (DocumentException e){
            e.printStackTrace();
        }
    }

    /**
     * 匹配出mappers，调用mapperElement方法
     * @return
     */
    public Configuration parse() {
        try {
            // 环境
            environmentsElement(root.element("environments"));
            // 解析映射器
            mapperElement(root.element("mappers"));
        } catch (Exception e) {
            throw new RuntimeException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
        return configuration;
    }
    /**
     * <environments default="development">
     * <environment id="development">
     * <transactionManager type="JDBC">
     * <property name="..." value="..."/>
     * </transactionManager>
     * <dataSource type="POOLED">
     * <property name="driver" value="${driver}"/>
     * <property name="url" value="${url}"/>
     * <property name="username" value="${username}"/>
     * <property name="password" value="${password}"/>
     * </dataSource>
     * </environment>
     * </environments>
     */
    private void environmentsElement(Element context) throws Exception {
        String environment = context.attributeValue("default");

        List<Element> environmentList = context.elements("environment");
        for (Element e : environmentList) {
            String id = e.attributeValue("id");
            if (environment.equals(id)) {
                // 事务管理器，这里就使用了别名映射
                TransactionFactory txFactory = (TransactionFactory) typeAliasRegistry.resolveAlias(e.element("transactionManager").attributeValue("type")).newInstance();

                // 数据源，这里就使用了别名映射
                Element dataSourceElement = e.element("dataSource");
                DataSourceFactory dataSourceFactory = (DataSourceFactory) typeAliasRegistry.resolveAlias(dataSourceElement.attributeValue("type")).newInstance();

                List<Element> propertyList = dataSourceElement.elements("property");
                Properties props = new Properties();
                for (Element property : propertyList) {
                    props.setProperty(property.attributeValue("name"), property.attributeValue("value"));
                }
                dataSourceFactory.setProperties(props);
                DataSource dataSource = dataSourceFactory.getDataSource();

                // 构建环境
                Environment.Builder environmentBuilder = new Environment.Builder(id)
                        .transactionFactory(txFactory)
                        .dataSource(dataSource);

                configuration.setEnvironment(environmentBuilder.build());
            }
        }
    }

    /**
     * 对总配置文件每个mapper的select语句进行扫描，然后注入到sql语句类中
     * @param mappers
     * @throws Exception
     */
//    private void mapperElement(Element mappers) throws Exception{
//        List<Element> mapperList = mappers.elements("mapper");
//        for (Element e : mapperList) {
//            String resource = e.attributeValue("resource");
//            InputStream inputStream = Resources.getResourceAsStream(resource);
//
//            // 在for循环里每个mapper都重新new一个XMLMapperBuilder，来解析
//            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource);
//            mapperParser.parse();
//        }
//    }


    private void mapperElement(Element mappers) throws Exception{
        //获取所有sql语句的集合
        List<Element> mapperList = mappers.elements("mapper");
        for (Element e : mapperList) {
            //获取resource属性值：也就是mapper文件地址
            String resource = e.attributeValue("resource");
            //通过地址去获取字符输入流
            Reader reader = Resources.getResourceAsReader(resource);
            SAXReader saxReader = new SAXReader();
            Document document = saxReader.read(new InputSource(reader));
            Element root = document.getRootElement();
            //命名空间,一个 Mapper XML 文件通常只能有一个 namespace
            String namespace = root.attributeValue("namespace");

            // SELECT
            List<Element> selectNodes = root.elements("select");
            for (Element node : selectNodes) {
                String id = node.attributeValue("id");
                String parameterType = node.attributeValue("parameterType");
                String resultType = node.attributeValue("resultType");
                String sql = node.getText();

                // 通过xpath表达式参数匹配
                //SELECT * FROM users WHERE id = #{userId} AND name = #{userName}->SELECT * FROM users WHERE id = ? AND name = ?
                Map<Integer, String> parameter = new HashMap<>();
                Pattern pattern = Pattern.compile("(#\\{(.*?)})");
                Matcher matcher = pattern.matcher(sql);
                for (int i = 1; matcher.find(); i++) {
                    String g1 = matcher.group(1);//matcher.group(1) 返回与正则表达式中第一个圆括号匹配的子串,即 #{param}。
                    String g2 = matcher.group(2);//matcher.group(2) 返回与正则表达式中第二个圆括号匹配的子串。即即去掉 #{} 后的部分，赋值给变量 g2
                    parameter.put(i, g2);//并注入到参数映射器（1：第一个参数名，2：第二个参数名）中
                    sql = sql.replace(g1, "?");//将#{XXX}替换成？
                }

                String msId = namespace + "." + id;
                String nodeName = node.getName();
                SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
                BoundSql boundSql = new BoundSql(sql, parameter, parameterType, resultType);

                MappedStatement mappedStatement = new MappedStatement.Builder(configuration, msId, sqlCommandType, boundSql).build();
                // 添加解析 SQL
                configuration.addMappedStatement(mappedStatement);
            }
            // 注册Mapper映射器，就是注册一个该接口和对应接口的代理工厂键值
            configuration.addMapper(Resources.classForName(namespace));
        }
    }
}