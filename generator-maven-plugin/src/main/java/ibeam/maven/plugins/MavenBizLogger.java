package ibeam.maven.plugins;


import ibeam.log.BizLogger;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.logging.Log;

/***
 * maven logger
 */
public class MavenBizLogger implements BizLogger {
    private Log logger;

    public MavenBizLogger(Log logger) {
        this.logger = logger;
    }

    //------------logger--------------------

    @Override
    public String getName() {
        return "maven";
    }

    private static String format(String format, Object... arguments) {
        if (arguments.length == 0) {
            return format;
        }
        try {
            format = StringUtils.replace(format, "{}", "%s", arguments.length);
            return String.format(format, arguments);
        } catch (Exception e) {
            return format;
        }
    }

    @Override
    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    @Override
    public void debug(String msg) {
        logger.debug(msg);
    }

    @Override
    public void debug(String format, Object arg) {
        logger.debug(format(format, arg));
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        logger.debug(format(format, arg1, arg2));
    }

    @Override
    public void debug(String format, Object... arguments) {
        logger.debug(format(format, arguments));
    }

    @Override
    public void debug(String msg, Throwable t) {
        logger.debug(msg, t);
    }

    @Override
    public boolean isInfoEnabled() {
        return logger.isInfoEnabled();
    }

    @Override
    public void info(String msg) {
        logger.info(msg);
    }

    @Override
    public void info(String format, Object arg) {
        logger.info(format(format, arg));
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        logger.info(format(format, arg1, arg2));
    }

    @Override
    public void info(String format, Object... arguments) {
        logger.info(format(format, arguments));
    }

    @Override
    public void info(String msg, Throwable t) {
        logger.info(msg, t);
    }

    @Override
    public boolean isWarnEnabled() {
        return logger.isWarnEnabled();
    }

    @Override
    public void warn(String msg) {
        logger.warn(msg);
    }

    @Override
    public void warn(String format, Object arg) {
        logger.warn(format(format, arg));
    }

    @Override
    public void warn(String format, Object... arguments) {
        logger.warn(format(format, arguments));
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        logger.warn(format(format, arg1, arg2));
    }

    @Override
    public void warn(String msg, Throwable t) {
        logger.warn(msg, t);
    }

    @Override
    public boolean isErrorEnabled() {
        return logger.isErrorEnabled();
    }

    @Override
    public void error(String msg) {
        logger.error(msg);
    }

    @Override
    public void error(String format, Object arg) {
        logger.error(format(format, arg));
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        logger.error(format(format, arg1, arg2));
    }

    @Override
    public void error(String format, Object... arguments) {
        logger.error(format(format, arguments));
    }

    @Override
    public void error(String msg, Throwable t) {
        logger.error(msg, t);
    }

    //------------logger--------------------
}
