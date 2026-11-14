# 3.1 使用JDBC读取和写入数据

## PDF第130页

3.1      使用JDBC读取和写入数据
    几十年以来,关系型数据库和SQL一直是数据持久化领域的首选方
案。尽管近年来涌现了许多可选的数据库类型,但是关系型数据库依然
是通用数据存储的首选,而且短期内不太可能撼动它的地位。
    在处理关系型数据的时候,Java开发人员有多种可选方案,其中
最常⻅的是JDBC和JPA。Spring同时支持这两种抽象形式,能够让
JDBC或JPA的使用更加容易。在本节中,我们将会讨论Spring如何支
持JDBC,然后会在3.2节讨论Spring对JPA的支持。
    Spring对JDBC的支持要归功于JdbcTemplate类。JdbcTemplate
提供了一种特殊的方式,通过这种方式,开发人员在对关系型数据库执
行SQL操作的时候能够避免使用JDBC时常⻅的繁文缛节和样板式代
码。
    为了更好地理解JdbcTemplate的功能,我们首先看一个不使用
JdbcTemplate的样例,看一下如何在Java中执行一个简单的查询,如
程序清单3.1所示。
                                程序清单3.1 不使用JdbcTemplate查询数据库
@Override
public Ingredient findOne(String id) {
  Connection connection = null;
  PreparedStatement statement = null;
  ResultSet resultSet = null;
  try {
    connection = dataSource.getConnection();
    statement = connection.prepareStatement(

## PDF第131页

"select id, name, type from Ingredient where id=?");
      statement.setString(1, id);
      resultSet = statement.executeQuery();
      Ingredient ingredient = null;
      if(resultSet.next()) {
        ingredient = new Ingredient(
            resultSet.getString("id"),
            resultSet.getString("name"),
            Ingredient.Type.valueOf(resultSet.getString("type")));
      }
      return ingredient;
    } catch (SQLException e) {
      // ??? What should be done here ???
    } finally {
      if (resultSet != null) {
        try {
          resultSet.close();
        } catch (SQLException e) {}
      }
      if (statement != null) {
        try {
          statement.close();
        } catch (SQLException e) {}
      }
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException e) {}
      }
    }
    return null;
}


  我向你保证,在程序清单3.1中存在查询数据库获取配料的那几行
代码,但我敢肯定你很难在JDBC代码段中将这个查询找出来,它被创
建连接、创建语句以及关闭连接、语句和结果集的清理功能完全包围了
起来。
  在创建连接、创建语句或执行查询的时候,可能会出现很多错误。
这就要求我们捕获SQLException,它对于找出哪里出现了问题或如何
解决问题可能有所帮助,也可能毫无用处。

## PDF第132页

是一个检查型异常,它需要在catch代码块中进行
   SQLException
处理。但是,对于常⻅的问题,如创建到数据库的连接失败或者输入的
查询有错误,在catch代码块中是无法解决的,并且有可能要继续抛出
以便于上游进行处理。作为对比,我们看一下使用JdbcTemplate的方
式,如程序清单3.2所示。
                                  程序清单3.2 使用JdbcTemplate查询数据库
private JdbcTemplate jdbc;

@Override
public Ingredient findOne(String id) {
  return jdbc.queryForObject(
      "select id, name, type from Ingredient where id=?",
      this::mapRowToIngredient, id);
}

private Ingredient mapRowToIngredient(ResultSet rs, int rowNum)
    throws SQLException {
  return new Ingredient(
      rs.getString("id"),
      rs.getString("name"),
      Ingredient.Type.valueOf(rs.getString("type")));
}


    程序清单3.2中的代码显然要比程序清单3.1中的原始JDBC示例简
单得多。这里没有创建任何的连接和语句。而且,在方法完成之后不需
要对这些对象进行清理。最后,这里也没有任何catch代码块中无法处
理的异常。剩下的代码仅仅关注执行查询(调用JdbcTemplate的
queryForObject())和将结果映射到Ingredient对象(在
mapRowToIngredient()方法中)上。

    程序清单3.2中的代码仅仅是在Taco Cloud应用中使用
JdbcTemplate持久化和读取数据的一个片段。接下来,我们着手实现
