package com.yueji.mall.platform.messaging;

import com.yueji.mall.identity.service.AccessTokens;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="yueji.replay.enabled",havingValue="true")
public class ReplayCommand implements ApplicationRunner {
    private final DeadLetterReplay replay;
    private final AccessTokens tokens;
    private final String file,event,reason;
    public ReplayCommand(DeadLetterReplay replay,AccessTokens tokens,
            @Value("${YUEJI_REPLAY_ACCESS_TOKEN_FILE}") String file,
            @Value("${yueji.replay.event-id}") String event,@Value("${yueji.replay.reason}") String reason) {
        this.replay=replay;this.tokens=tokens;this.file=file;this.event=event;this.reason=reason;
    }
    public void run(ApplicationArguments args) throws Exception {
        Path path=Path.of(file);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>4096) throw new IllegalArgumentException("需要受限普通令牌文件");
        var actor=tokens.authenticate(Files.readString(path).trim());
        if(!replay.replay(actor,event,reason)) throw new IllegalStateException("死信队列为空，未执行重放");
    }
}
