package utils;


import meteordevelopment.meteorclient.MeteorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.loader.impl.FabricLoaderImpl.MOD_ID;

public class LogUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void error(String msg)
    {
        MeteorClient.LOG.error(msg);
        LOGGER.error(msg);
    }

    public static void log(String msg)
    {
        MeteorClient.LOG.info(msg);
    }

}
