package com.yueji.mall;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.platform.service.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class ImageSafetyTests {
    @Test void realWebpIsDecodedAndReencodedAsPngWithoutOriginalPayload() throws Exception {
        byte[] webp=getClass().getResourceAsStream("/fixtures/one-pixel.webp").readAllBytes();
        byte[] png=new SafeImageCodec().encode(new MockMultipartFile("file","pixel.webp","image/webp",webp));
        assertThat(png).startsWith(new byte[]{(byte)137,80,78,71});
        assertThat(ImageIO.read(new ByteArrayInputStream(png)).getWidth()).isEqualTo(2);
        assertThat(png).isNotEqualTo(webp);
    }
    @Test void compressedImageDimensionsAreCheckedBeforePixelAllocation() throws Exception {
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(10001,1,BufferedImage.TYPE_INT_RGB),"png",output);
        var codec=new SafeImageCodec();
        assertThatThrownBy(()->codec.encode(new MockMultipartFile("file","wide.png","image/png",output.toByteArray())))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(com.yueji.mall.common.api.ErrorCode.FILE_TOO_LARGE));
        assertThatThrownBy(()->codec.encode(new MockMultipartFile("file","script.svg","image/svg+xml","<svg/>".getBytes()))).isInstanceOf(BusinessException.class);
    }
    @Test void generatedStorageKeysStayWithinSeparateRootsAndUnknownPathsAreRejected(@TempDir Path directory) {
        var storage=new FileStorage(directory.toString());
        byte[] bytes={1,2,3};String publicKey=storage.write(bytes,true),privateKey=storage.write(bytes,false);
        assertThat(publicKey).startsWith("public/");assertThat(privateKey).startsWith("private/");
        assertThat(storage.read(privateKey)).isEqualTo(bytes);
        assertThatThrownBy(()->storage.read("../private/escape.png")).isInstanceOf(BusinessException.class);
        storage.discard(privateKey);assertThatThrownBy(()->storage.read(privateKey)).isInstanceOf(BusinessException.class);
    }
}
