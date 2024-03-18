package cn.evole.onebot.mirai.core.session;

import cn.evole.onebot.mirai.OneBotMirai;
import cn.evole.onebot.mirai.config.PluginConfig;
import cn.evole.onebot.mirai.config.PluginConfig.BotConfig;
import cn.evole.onebot.mirai.core.ApiMap;
import cn.evole.onebot.mirai.core.EventMap;
import cn.evole.onebot.mirai.util.BaseUtils;
import cn.evole.onebot.mirai.util.HttpUtils;
import cn.evole.onebot.mirai.web.http.OneBotHttpServer;
import cn.evole.onebot.mirai.web.websocket.OneBotWSClient;
import cn.evole.onebot.mirai.web.websocket.OneBotWSServer;
import cn.evole.onebot.sdk.event.IgnoreEvent;
import cn.evole.onebot.sdk.util.json.GsonUtils;
import com.google.gson.Gson;
import lombok.Getter;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.event.events.BotEvent;
import net.mamoe.mirai.utils.MiraiLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Description:功能实现单元
 * Author: cnlimiter
 * Date: 2022/10/8 7:19
 * Version: 1.0
 */
@Getter
public class BotSession {

    private final Bot bot;
    private final ApiMap apiImpl;
    private final BotConfig botConfig;
    private final OneBotHttpServer httpServer;
    private final OneBotWSServer websocketServer;
    private final List<OneBotWSClient> websocketClient = new ArrayList<>();
    private final MiraiLogger miraiLogger = OneBotMirai.logger;


    @Override
    public String toString() {
        return """
                Bot Id: %s,
                Bot Config: %s,
                OneBot HTTP Server: %s,
                OneBot WS Server: %s,
                OneBot WS Clients: %s
                """.formatted(this.bot.getId(), this.botConfig.toString(), this.httpServer.getAddress(), this.websocketServer.getAddress(), this.websocketClient.size())
                ;
    }

    public BotSession(Bot bot, BotConfig botConfig) {
        this.bot = bot;
        this.apiImpl = new ApiMap(bot);
        this.botConfig = botConfig;
        this.httpServer = new OneBotHttpServer(this, botConfig);
        this.websocketServer = new OneBotWSServer(this, botConfig);


        if (this.botConfig.getHttp().getEnable()) {
            this.miraiLogger.info(String.format("创建正向HTTP服务器: %s:%s", botConfig.getHttp().getHost(), botConfig.getHttp().getPort()));
            this.httpServer.create();
        }
        if (this.botConfig.getWs().getEnable()) {
            this.miraiLogger.info(String.format("创建正向WS服务器: %s:%s", botConfig.getWs().getWsHost(), botConfig.getWs().getWsPort()));
            this.websocketServer.create();
        }

        for (PluginConfig.WSReverseConfig ws_re : botConfig.getWsReverse()) {
            if (ws_re.getEnable()) {
                OneBotWSClient client = new OneBotWSClient(
                        this, ws_re
                );
                client.create();
                this.miraiLogger.info(String.format("创建反向WS服务器: %s:%s", ws_re.getReverseHost(), ws_re.getReversePort()));
                this.websocketClient.add(client);
            }
        }
    }

    public void close() {
        if (this.botConfig.getHttp().getEnable())
            this.httpServer.close();
        if (this.botConfig.getWs().getEnable())
            this.websocketServer.close();
        this.websocketClient.forEach(OneBotWSClient::close);
    }

    private final ThreadLocal<Gson> gsonTl = ThreadLocal.withInitial(GsonUtils::getGson);

    public void triggerEvent(BotEvent event) {
        var e = EventMap.toDTO(event);
        var json = gsonTl.get().toJson(e);
        if (!(e instanceof IgnoreEvent)) {
            var debug = PluginConfig.INSTANCE.getDebug();
            if (this.botConfig.getHttp().getEnable()) {
                if (debug) this.miraiLogger.info("上报HTTP事件");
                Properties header = new Properties();
                header.putIfAbsent("User-Agent", "OneBotMirai/" + OneBotMirai.VERSION);
                header.putIfAbsent("X-Self-ID", bot.getId());
                if (!botConfig.getHttp().getSecret().isEmpty())
                    header.putIfAbsent("X-Signature", BaseUtils.getSha(json, botConfig.getHttp().getSecret(), "SHA-1", false));
                var response = HttpUtils.jsonPost(this.miraiLogger, this.botConfig.getHttp().getPostUrl(), json, header);
                if (response != null) {
                    if (debug) this.miraiLogger.info("收到HTTP上报响应 %s".formatted(response));
                    try {
                        //todo 快速操作
                        //var params = hashMapOf("context" to sentJson, "operation" to respJson)
                        //miraiApi.handleQuickOperation(params)
                    } catch (Exception e1) {
                        this.miraiLogger.error("解析HTTP上报返回数据失败");
                    }
                }
            }

            if (this.botConfig.getWs().getEnable()) {
                if (debug) this.miraiLogger.info("广播正向websocket事件");
                this.websocketServer.broadcast(json);
            }

            for (PluginConfig.WSReverseConfig ws_re : this.botConfig.getWsReverse()) {
                if (ws_re.getEnable()) {
                    long sendCount = this.websocketClient.stream().filter(client -> {
                        try {
                            if (client.isOpen()) {
                                client.send(json);
                            }
                        } catch (Exception ex) {
                            this.miraiLogger.warning("广播反向websocket事件失败:", ex);
                        }
                        return client.isOpen();
                    }).count();
                    if (debug) this.miraiLogger.info(String.format("广播反向websocket事件, 共发送 :%d", sendCount));
                }
            }

        }
    }

}
