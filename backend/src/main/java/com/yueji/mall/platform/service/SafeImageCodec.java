package com.yueji.mall.platform.service;

import com.yueji.mall.common.api.*;
import java.io.*;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class SafeImageCodec {
    public static final long MAX_BYTES=5L*1024*1024;
    public byte[] encode(MultipartFile file) {
        if(file.isEmpty()) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        if(file.getSize()>MAX_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        String name=file.getOriginalFilename();
        if(name==null || name.length()>255 || name.contains("/") || name.contains("\\") || name.contains(":") || name.contains("\0")) throw new BusinessException(ErrorCode.PARAM_INVALID);
        String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        String format=switch(extension) {case "jpg","jpeg"->"JPEG";case "png"->"PNG";case "webp"->"WEBP";default->throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);};
        String mime="image/"+(format.equals("JPEG")?"jpeg":format.toLowerCase(Locale.ROOT));
        if(!mime.equals(file.getContentType())) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            var readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext()) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
            var reader=readers.next();
            try {
                if(!format.equalsIgnoreCase(reader.getFormatName())) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
                reader.setInput(stream,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                // 先读尺寸再分配像素，限制压缩图像造成的内存放大；不复制原图元数据或附加字节。
                if(width<1 || height<1 || width>10000 || height>10000 || (long)width*height>20000000L) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
                var image=reader.read(0);var output=new ByteArrayOutputStream();
                if(!ImageIO.write(image,"png",output)) throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
                if(output.size()>MAX_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
                return output.toByteArray();
            } finally {reader.dispose();}
        } catch(IOException | IllegalArgumentException error) {throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);}
    }
}
