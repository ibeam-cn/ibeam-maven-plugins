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
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.IllegalClassFormatException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Implementation of the ibeam code generator
 *
 * @author zhushaoping
 * @version $Id$
 * @goal generate
 * @phase generate-sources
 * @requiresProject true
 */
//@Mojo(name = "check", defaultPhase = LifecyclePhase.GENERATE_SOURCES,
//		requiresDependencyResolution = ResolutionScope.COMPILE,
//		threadSafe = false )process-sources
public class CodeGenerateMojo extends AbstractMojo {
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

    /**
     * set if generator should overwrite the exist code or update file
     *
     * @parameter expression="${overWriteDao}" default-value="false"
     * @since 1.0
     */
    private boolean overWriteClass;

    /**
     * tables which won't generate code for, support regex
     *
     * @parameter expression="${ignoreTables}" default-value=""
     * @since 1.0
     */
    private String ignoreTables;

    /**
     * source code encoding, default is UTF-8
     *
     * @parameter expression="${encoding}" default-value="UTF-8"
     * @since 1.0
     */
    private String encoding;

    /**
     * base package
     *
     * @parameter expression="${basePackage}"
     * @since 1.0
     */
    private String basePackage;

    /**
     * database to read
     *
     * @parameter expression="${database}"
     * @required
     * @since 1.0
     */
    private String database;

    /**
     * replace table name with blank string, support regex
     *
     * @parameter expression="${tableIgnoreRegex}" default-value="([-_][0-9]+)"
     * @since 1.0
     */
    private String tableIgnoreRegex;

    /**
     * when convert table name to class name, which chars will be ignored
     *
     * @parameter expression="${classIgnoreRegex}" default-value=""
     * @since 1.0
     */
    private String classIgnoreRegex;

    /**
     * freeMaker template root path
     *
     * @parameter expression="${templatePath}" default-value=""
     * @since 1.0
     */
    private String templatePath;

    /**
     * Skip generator
     *
     * @parameter expression="${skip}" default-value=false
     */
    private boolean skip;


    private String getJdbcParam(Properties properties, String db, String env, String name) {
        String key = env + ".jdbc.";
        if (BeamUtils.isNotBlank(db)) {
            key += db + ".";
        }
        key += name;
        return properties.getProperty(key);
    }

    private boolean shouldGenerateWebApi() {
        for (Dependency dependency : project.getDependencies()) {
            if (dependency.getGroupId().equalsIgnoreCase("cn.ibeam.web")) {
                return true;
            }
        }
        return false;
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

            if (BeamUtils.isBlank(this.tableIgnoreRegex)) {
                this.tableIgnoreRegex = "[-_][0-9]+";
            }
            final Pattern tableNamePatten = Pattern.compile(this.tableIgnoreRegex);
            Converter<String, String> tableNameBuilder = new Converter<String, String>() {
                @Override
                public String convert(String value) throws IllegalArgumentException {
                    return tableNamePatten.matcher(value).replaceAll("");
                }
            };

            if (BeamUtils.isBlank(this.basePackage)) {
                this.basePackage = "";
                getLog().info("basePackage not set, will generate code at src/main/java/");
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
            getLog().info("classIgnore: " + this.classIgnoreRegex);
            final Pattern classNamePattern = BeamUtils.isNotBlank(this.classIgnoreRegex) ?
                    Pattern.compile(this.classIgnoreRegex) : null;
            boolean shouldGenerateWebApi = this.shouldGenerateWebApi();
            if (!shouldGenerateWebApi) {
                getLog().info("won't generate web related class as there is no cn.ibeam.web dependency in POM");
            }
            for (Table table : tables) {
                String domain = StringUtils.replaceEachRepeatedly(this.database,
                        new String[]{"_", "-"}, new String[]{".", "."});
                String className = table.getName();
                if (null != classNamePattern) {
                    className = classNamePattern.matcher(className).replaceAll("");
                }
                table.setClassName(StringUtils.capitalize(BeamUtils.toPropertyName(className)));
                getLog().info("table: [" + table.getName() + "] to: [" + table.getClassName() + "]");
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
                if (codeFile.exists()) {
                    if (this.overWriteClass) {
                        getLog().info(codeFile.getAbsolutePath() + " will be overwrite " + table.getName());
                        code = CodeUtils.overWriteEntity(table, FileUtils.readFileToString(codeFile, this.encoding));
                        FileUtils.writeStringToFile(codeFile, code, this.encoding);
                    } else {
                        getLog().info(codeFile.getAbsolutePath() + " exist, will ignore " + table.getName());
                    }
                } else {
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

                if (shouldGenerateWebApi) {
                    code = render(writer, codeMaker, "api_controller" + suffix, model);
                    codeFile = getCodeFile(sourceDir, code);
                    if (codeFile.exists()) {
                        getLog().info(codeFile.getAbsolutePath() + " exist, will ignore for " + table.getName());
                    } else {
                        FileUtils.writeStringToFile(codeFile, code, this.encoding);
                    }
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
        File parentFile = FileUtils.getFile(sourceDir, StringUtils.split(packageName, '.'));
        return FileUtils.getFile(parentFile, classDeclaration.getNameAsString() + ".java");
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
        if (!filePath.exists()) {
            filePath = FileUtils.getFile(rootDir, "src", "test", "resources", "application.properties");
        }
        if (!filePath.exists()) {
            throw new IOException("application.properties must set under /src/main/resources/ or /src/test/resources/");
        }
        getLog().info("application.properties at " + filePath.getAbsolutePath());
        try (InputStream in = new FileInputStream(filePath)) {
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
        if (skip) {
            getLog().info("IBeam code generator is skipped.");
            return;
        }
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
