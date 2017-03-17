package ibeam.maven.plugins;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import freemarker.template.TemplateException;
import ibeam.BeamUtils;
import ibeam.Converter;
import ibeam.annotation.entity.ShardByMod;
import ibeam.code.CodeMaker;
import ibeam.code.CodeUtils;
import ibeam.code.ReusableStringWriter;
import ibeam.code.template.FreeMaker;
import ibeam.jdbc.Column;
import ibeam.jdbc.Table;
import ibeam.jdbc.TableParser;
import ibeam.log.BizLogger;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.IllegalClassFormatException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Implementation of the generator:code goal.
 *
 * @author zhushaoping
 * @version $Id$
 * @goal code
 * @phase process-sources
 * @requiresProject true
 */
//@Mojo(name = "check", defaultPhase = LifecyclePhase.GENERATE_SOURCES,
//		requiresDependencyResolution = ResolutionScope.COMPILE,
//		threadSafe = false )
public class CodeGenerateMojo extends AbstractMojo {

    /**
     * set if generator should overwrite the exist code or update file
     *
     * @parameter expression="${overWriteDao}" default-value="false"
     * @since 1.0
     */
    @Parameter(property = "overWriteClass", defaultValue = "false")
    private boolean overWriteClass;

    /**
     * tables which won't generate code for, support regex
     *
     * @parameter expression="${ignoreTables}" default-value=""
     * @since 1.0
     */
    @Parameter(property = "ignoreTables", defaultValue = "")
    private String ignoreTables;

    /**
     * 要忽略的表名,使用,分隔
     *
     * @parameter expression="${ignoreTables}" default-value=""
     * @since 1.0
     */
    @Parameter(property = "encoding", defaultValue = "UTF-8")
    private String encoding;

    /**
     * base package
     *
     * @parameter expression="${basePackage}"
     * @since 1.0
     */
    @Parameter(property = "basePackage", required = true)
    private String basePackage;

    /**
     * database to read
     *
     * @parameter expression="${database}"
     * @since 1.0
     */
    @Parameter(property = "database", required = true)
    private String database;

    /**
     * replace table name with blank string, support regex
     *
     * @parameter expression="${tableReplaceRegex}" default-value="([-_][0-9]+)"
     * @since 1.0
     */
    @Parameter(property = "tableReplaceRegex")
    private String tableReplaceRegex;

    /**
     * freeMaker template root path
     *
     * @parameter expression="${templatePath}" default-value=""
     * @since 1.0
     */
    @Parameter(property = "templatePath")
    private String templatePath;

    /**
     * Root location of the local maven repository.
     *
     * @parameter expression="${localRepository}"
     * @readonly
     * @required
     */
    private ArtifactRepository localMavenRepository;


    /**
     * Current project definition
     *
     * @parameter expression="${project}"
     * @readonly
     */
    private MavenProject project;


    private String getJdbcParam(Properties properties, String db, String env, String name) {
        String key = env + ".jdbc.";
        if (BeamUtils.isNotBlank(name)) {
            key += db + ".";
        }
        key += name;
        return properties.getProperty(key);
    }

    /**
     * Main entry point for sub classes of this abstract implementation.
     * Execute clean local repository sub routine according to the given mojo goal and options.
     *
     * @throws MojoExecutionException
     * @throws MojoFailureException
     */
    protected void executeCleanLocalRepositoryGoals() throws MojoExecutionException, MojoFailureException {

        File rootDir = project.getBasedir();
        File sourceDir = FileUtils.getFile(rootDir, "src", "main", "java");
        getLog().debug("project: " + rootDir.getAbsolutePath());
        getLog().debug("pom:" + project.getFile().getAbsolutePath());
        try {
            getLog().info("BEGIN");

            if (BeamUtils.isBlank(this.tableReplaceRegex)) {
                this.tableReplaceRegex = "[-_][0-9]+";
            }
            final Pattern pattern = Pattern.compile(this.tableReplaceRegex);
            Converter<String, String> tableNameBuilder = new Converter<String, String>() {
                @Override
                public String convert(String value) throws IllegalArgumentException {
                    return pattern.matcher(value).replaceAll("");
                }
            };

            if (BeamUtils.isBlank(this.basePackage)) {
                getLog().info("basePackage not set, will guess from directory in project");
                File[] files = sourceDir.listFiles();
                if (null == files || files.length == 0) {
                    this.basePackage = "";
                }
            }

            Properties properties = this.loadProperty(rootDir);
            String env = properties.getProperty("environment", "dev");

            String userName = getJdbcParam(properties, this.database, env, "username");
            String password = getJdbcParam(properties, this.database, env, "password");
            String jdbcUrl = getJdbcParam(properties, this.database, env, "url");

            if (BeamUtils.isBlank(jdbcUrl)) {
                if (BeamUtils.isNotBlank(this.database)) {
                    throw new IllegalArgumentException("database setting in application properties need to set for " + this.database);
                } else {
                    throw new IllegalArgumentException("database name need to set!");
                }
            }

            TableParser tableParser = new TableParser(userName, password, jdbcUrl);
            tableParser.setLogger(new MavenBizLogger(getLog()));
            Collection<Table> tables = tableParser.parse(this.ignoreTables, tableNameBuilder);
            CodeMaker codeMaker = buildCodeMaker(rootDir);
            ReusableStringWriter writer = new ReusableStringWriter(1000);
            for (Table table : tables) {
                String domain = StringUtils.replaceAll(table.getDatabase(), "_-", ".");
                table.setClassName(StringUtils.capitalize(BeamUtils.toPropertyName(table.getName())));
                if (table.getShardCount() > 1) {
                    table.addPackage(ShardByMod.class);
                    table.addAnnotation("@ShardByMod(value = " + table.getShardCount() + ")");
                }

                for (Column column : table.getColumns()) {
                    column.setField(BeamUtils.toPropertyName(column.getName()));
                }

                Map<String, Object> model = new HashMap<>();
                model.put("table", table);
                model.put("domain", domain);

                String code = render(writer, codeMaker, "entity", model);
                File codeFile = getCodeFile(sourceDir, code);
                if (codeFile.exists() && this.overWriteClass) {
                    code = CodeUtils.overWriteEntity(table, FileUtils.readFileToString(codeFile, this.encoding));
                    FileUtils.writeStringToFile(codeFile, code, this.encoding);
                }

                String suffix = table.getShardCount() > 1 ? "_shard" : "";
                code = render(writer, codeMaker, "dao" + suffix, model);
                codeFile = getCodeFile(sourceDir, code);
                if (codeFile.exists()) {
                    getLog().info(codeFile.getAbsolutePath() + " exist, will ignore for " + table.getName());
                } else {
                    FileUtils.writeStringToFile(codeFile, code, this.encoding);
                }

                code = render(writer, codeMaker, "service" + suffix, model);

                codeFile = getCodeFile(sourceDir, code);
                if (codeFile.exists()) {
                    getLog().info(codeFile.getAbsolutePath() + " exist, will ignore for " + table.getName());
                } else {
                    FileUtils.writeStringToFile(codeFile, code, this.encoding);
                }

                code = render(writer, codeMaker, "api_controller" + suffix, model);
                codeFile = getCodeFile(sourceDir, code);
                if (codeFile.exists()) {
                    getLog().info(codeFile.getAbsolutePath() + " exist, will ignore for " + table.getName());
                } else {
                    FileUtils.writeStringToFile(codeFile, code, this.encoding);
                }
            }

            getLog().info("END");
        } catch (Exception e) {
            getLog().error("Code Generate error", e);
        }
        // [{"directory":"/Users/juju/work/yitao/svn/bi/trunk/bi/src/main/resources",
        // "excludes":[],"filtering":"true","includes":[]}]
//		getLog().info(JSON.toJSONString(project.getResources()));
        ///Users/juju/work/yitao/svn/bi/trunk/bi/pom.xml

    }

    private CodeMaker buildCodeMaker(File rootDir) throws IOException, TemplateException {
        File templateDir = null;
        if (BeamUtils.isNotBlank(this.templatePath)) {
            if (this.templatePath.startsWith("/")) {
                templateDir = new File(this.templatePath);
            } else if (this.templatePath.startsWith("classpath:")) {
                templateDir = FileUtils.getFile(rootDir, "src", "main", "resources", this.templatePath.substring(10));
                if (!templateDir.exists()) {
                    templateDir = FileUtils.getFile(rootDir, "src", "main", "java", this.templatePath.substring(10));
                }
            } else {
                templateDir = FileUtils.getFile(rootDir, this.templatePath);
            }
        }
        return new FreeMaker(this.basePackage, templateDir, this.encoding);
    }

    private File getCodeFile(File sourceDir, String code) throws IllegalClassFormatException {
        CompilationUnit cu = JavaParser.parse(code);
        Optional<PackageDeclaration> packageDeclaration = cu.getPackageDeclaration();
        if (!packageDeclaration.isPresent()) {
            throw new IllegalClassFormatException("no package set in the code for entity: \n" + code);
        }
        String packageName = packageDeclaration.get().getNameAsString();
        ClassOrInterfaceDeclaration classDeclaration = (ClassOrInterfaceDeclaration) cu.getTypes().get(0);
        String className = classDeclaration.getNameAsString();
        return FileUtils.getFile(sourceDir, StringUtils.split(packageName + "." + className, "."));
    }

    private String render(ReusableStringWriter writer, CodeMaker codeMaker, String name, Map<String, Object> model) throws IOException {
        try {
            codeMaker.render(name, model, writer);
            return writer.toString();
        } finally {
            writer.reset();
        }
    }

    private Properties loadProperty(File rootDir) throws IOException {
        File filePath = FileUtils.getFile(rootDir, "src", "main", "resources", "application.properties");
        try (InputStream in =
                     ClassLoader.getSystemResourceAsStream(filePath.getAbsolutePath())) {
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        }
    }


    /**
     * Initialize and check the execution context of the Mojo :
     * - read access to the local repository folder
     * - unexpected parameter
     * - pattern syntax exception
     *
     * @return a file representing the current maven local repository
     * @throws MojoExecutionException
     * @throws MojoFailureException
     */
    private File initializeAndCheckMojoContext() throws MojoExecutionException, MojoFailureException {
        final File localRepositoryFolder = new File(localMavenRepository.getBasedir());

        if (!localRepositoryFolder.exists()) {
            throw new MojoExecutionException(Enumeres.EXCEPTION.LOCAL_MAVEN_REPOSITORY_UNAVAILABLE + localRepositoryFolder);
        }

        if (!localRepositoryFolder.canWrite()) {
            throw new MojoFailureException(Enumeres.EXCEPTION.LOCAL_MAVEN_REPOSITORY_PERMISSION_DENIED + localRepositoryFolder);
        }

        return localRepositoryFolder;
    }


    /**
     * Implementation of the execute() method for the clean-local-repository:list goal.
     *
     * @throws MojoExecutionException
     * @throws MojoFailureException
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        this.executeCleanLocalRepositoryGoals();
    }


    /**
     * This abstract method implementation define the deletion mode associated with the current goal.
     *
     * @return true in the current "clean" goal context
     */
    protected boolean isDeleteModeActivated() {
        return true;
    }

}
