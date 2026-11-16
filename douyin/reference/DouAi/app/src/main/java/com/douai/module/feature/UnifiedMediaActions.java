package com.douai.module.feature;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.widget.Toast;
import com.douai.module.config.LocalOptions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Selects media already present on the current host model; never constructs a URL from a VID. */
public final class UnifiedMediaActions {
    public enum Encoding { AUTO, H264, H265, DOWN }
    static final class Source {
        final String url, extension, mime, label;
        final boolean image, audio;
        Source(String url, String extension, String mime, String label, boolean image, boolean audio) {
            this.url=url; this.extension=extension; this.mime=mime; this.label=label; this.image=image; this.audio=audio;
        }
    }
    private UnifiedMediaActions() { }

    static String videoSource(Object work, Encoding encoding) {
        Object video=value(work,"video");
        if(video==null)return null;
        if(encoding==Encoding.DOWN){String found=downloadUrl(value(video,"download_addr"));return found!=null?found:downloadUrl(value(video,"new_download_addr"));}
        if(encoding==Encoding.H264){
            String explicit=url(value(video,"play_addr_h264"));if(explicit!=null)return explicit;
            String primary=markedSource(video,false);return primary!=null?primary:bitrate(video,false);
        }
        if(encoding==Encoding.H265){
            for(String key:new String[]{"play_addr_265","play_addr_bytevc1"}){String found=url(value(video,key));if(found!=null)return found;}
            String primary=markedSource(video,true);return primary!=null?primary:bitrate(video,true);
        }
        for(String key:new String[]{"play_addr","play_addr_h264","play_addr_265","play_addr_bytevc1","play_addr_lowbr"}){
            String found=url(value(video,key));if(found!=null)return found;
        }
        Object rates=value(video,"bit_rate");
        for(Object rate:items(rates))for(String key:new String[]{"play_addr","play_addr_265","play_addr_bytevc1"}){
            String found=url(value(rate,key));if(found!=null)return found;
        }
        return null;
    }
    private static String downloadUrl(Object model) {
        String found = url(model);
        // UrlModel carries a separate explicit download list. Keep it exclusive to DOWN;
        // neither an AUTO playback source nor a no-watermark guarantee follows from it.
        return found != null ? found : url(value(model, "download_url_list"));
    }
    private static String bitrate(Object video,boolean hevc){
        for(Object rate:items(value(video,"bit_rate"))){
            if(hevc){for(String key:new String[]{"play_addr_265","play_addr_bytevc1"}){String found=url(value(rate,key));if(found!=null)return found;}}
            String marked=markedSource(rate,hevc);if(marked!=null)return marked;
        }
        return null;
    }
    private static String markedSource(Object model,boolean hevc){
        Object h265=value(model,"is_h265"),bytevc=value(model,"is_bytevc1");
        boolean markedHevc=h265 instanceof Number&&((Number)h265).intValue()!=0
                ||bytevc instanceof Number&&((Number)bytevc).intValue()!=0;
        boolean markedAvc=h265 instanceof Number&&((Number)h265).intValue()==0
                &&(!(bytevc instanceof Number)||((Number)bytevc).intValue()==0);
        return (hevc?markedHevc:markedAvc)?url(value(model,"play_addr")):null;
    }
    static Source avatar(Object work){
        Object author=value(work,"author");
        // avatar_larger is the host's full-sized source; never upscale or remove URL resize parameters.
        String found=url(value(author,"avatar_larger"));
        if(found==null)return null;
        String ext=extension(found,"jpg");
        return new Source(found,ext,imageMime(ext),"作者头像原图",true,false);
    }
    static List<Source> commentVideos(Object comment){
        ArrayList<Source> sources=new ArrayList<>();LinkedHashSet<String> seen=new LinkedHashSet<>();
        for(Object entry:items(value(comment,"video_list"))){
            Object work=value(entry,"alias_aweme");String found=videoSource(work,Encoding.AUTO);
            if(found==null)found=videoSource(work,Encoding.DOWN);
            if(found!=null&&seen.add(found))sources.add(new Source(found,"mp4","video/mp4","评论视频",false,false));
        }
        return sources;
    }
    static List<Source> commentAudio(Object comment){
        Object audio=value(comment,"comment_audio");Object raw=value(audio,"content");
        LinkedHashSet<String> urls=new LinkedHashSet<>();
        if(raw instanceof String){String text=((String)raw).trim();if(text.length()>512*1024)return Collections.emptyList();
            if(isAudioUrl(text))urls.add(text);else if(boundedJson(text))try{raw=new JSONTokener(text).nextValue();}catch(Exception ignored){raw=null;}else raw=null;}
        collectPlaybackUrls(raw,urls,0,new int[]{1024});
        ArrayList<Source> sources=new ArrayList<>();
        String format=audioFormat(raw);
        // The URL list may contain CDN backups for one voice message; save one recording, not copies.
        for(String found:urls){String ext=extension(found,format);sources.add(new Source(found,ext,audioMime(ext),"评论语音",false,true));break;}
        return sources;
    }
    private static void collectPlaybackUrls(Object node,LinkedHashSet<String> urls,int depth,int[] budget){
        if(node==null||depth>10||budget[0]--<=0||urls.size()>=8)return;
        if(node instanceof JSONArray){JSONArray a=(JSONArray)node;for(int i=0;i<Math.min(64,a.length());i++)collectPlaybackUrls(a.opt(i),urls,depth+1,budget);return;}
        if(!(node instanceof JSONObject))return;
        JSONObject o=(JSONObject)node;var keys=o.keys();
        while(keys.hasNext()&&budget[0]-- >0){String key=keys.next();Object v=o.opt(key);String normalized=key.replace("_","").toLowerCase(Locale.ROOT);
            if(List.of("url","urllist","mainurl","backupurl","mainplayurl","backupplayurl","playurl","playaddr","downloadurl","resourceurl").contains(normalized))addUrls(v,urls,depth,budget);
            if(v instanceof JSONObject||v instanceof JSONArray)collectPlaybackUrls(v,urls,depth+1,budget);
        }
    }
    private static void addUrls(Object value,LinkedHashSet<String> urls,int depth,int[] budget){
        if(depth>10||budget[0]--<=0||urls.size()>=8)return;
        if(value instanceof String){String decoded=playbackUrl((String)value);if(decoded!=null)urls.add(decoded);}
        else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<Math.min(32,a.length());i++)addUrls(a.opt(i),urls,depth+1,budget);}
    }
    private static String playbackUrl(String text){
        if(isAudioUrl(text))return text;
        if(text.length()>16384||text.isBlank())return null;
        try{String decoded=new String(Base64.decode(text,Base64.DEFAULT),StandardCharsets.UTF_8);return isAudioUrl(decoded)?decoded:null;}
        catch(IllegalArgumentException invalid){return null;}
    }
    private static boolean boundedJson(String text){
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>12)return false;}else if(c=='}'||c==']')depth--;}
        return depth==0&&!quoted;
    }
    private static String audioFormat(Object node){return audioFormat(node,0,new int[]{1024});}
    private static String audioFormat(Object node,int depth,int[] budget){
        if(depth>10||budget[0]--<=0)return "audio";
        if(node instanceof JSONObject){JSONObject object=(JSONObject)node;
            for(String name:new String[]{"format","Format","container","Container"}){Object v=object.opt(name);if(v instanceof String){String f=((String)v).toLowerCase(Locale.ROOT);if(f.equals("mp4"))return "m4a";if(List.of("mp3","m4a","aac","opus","ogg","wav","flac").contains(f))return f;}}
            var keys=object.keys();int checked=0;while(keys.hasNext()&&checked++<32){Object v=object.opt(keys.next());if(v instanceof JSONObject||v instanceof JSONArray){String f=audioFormat(v,depth+1,budget);if(!f.equals("audio"))return f;}}
        }else if(node instanceof JSONArray){JSONArray array=(JSONArray)node;for(int i=0;i<Math.min(32,array.length());i++){String f=audioFormat(array.opt(i),depth+1,budget);if(!f.equals("audio"))return f;}}
        return "audio";
    }
    static boolean queue(Context context,Source source,String fileName,LocalOptions options,Consumer<String> log){
        String tree=options==null?"":options.downloadSaveTree();
        if(source.image)return MediaStoreDownloader.downloadImage(context,source.url,fileName,tree,log);
        if(source.audio)return MediaStoreDownloader.downloadAudioSource(context,source.url,fileName,source.mime,tree,log);
        return MediaStoreDownloader.download(context,source.url,fileName,false,tree,log);
    }
    static void saveComment(Context context,Object comment,boolean audio,LocalOptions options,Consumer<String> log){
        List<Source> sources=audio?commentAudio(comment):commentVideos(comment);
        if(sources.isEmpty()){Toast.makeText(context,audio?"当前评论没有已下发的可保存语音地址，请先播放后重试":"当前评论没有可保存的视频地址",Toast.LENGTH_SHORT).show();return;}
        Object cid=value(comment,"cid");String id=cid instanceof String?((String)cid).replaceAll("[^A-Za-z0-9_-]", ""):"";
        String base="DouAi_comment_"+(id.isEmpty()?System.currentTimeMillis():id);int queued=0,index=0;
        for(Source source:sources){String name=base+"_"+(++index)+"."+source.extension;if(queue(context,source,name,options,log))queued++;}
        Toast.makeText(context,queued>0?"已加入 "+queued+" 个评论媒体任务":"评论媒体未加入任务，请检查地址和保存目录",Toast.LENGTH_SHORT).show();
    }
    static Object value(Object object,String key){
        if(object instanceof JSONObject){Object v=((JSONObject)object).opt(key);return v==JSONObject.NULL?null:v;}
        return DownloadActionHook.jsonValue(object,key);
    }
    private static Iterable<?> items(Object value){
        if(value instanceof Iterable<?>){ArrayList<Object> list=new ArrayList<>();for(Object item:(Iterable<?>)value){list.add(item);if(list.size()>=128)break;}return list;}
        if(value instanceof JSONArray){ArrayList<Object> list=new ArrayList<>();JSONArray a=(JSONArray)value;for(int i=0;i<Math.min(128,a.length());i++)list.add(a.opt(i));return list;}
        return Collections.emptyList();
    }
    private static String url(Object model){
        Object raw=model instanceof Iterable<?>||model instanceof JSONArray?model:value(model,"url_list");
        for(Object value:items(raw))if(value instanceof String&&isFileUrl((String)value))return (String)value;
        return model instanceof String&&isFileUrl((String)model)?(String)model:null;
    }
    private static boolean isFileUrl(String url){
        if(!MediaStoreDownloader.isHttpUrl(url))return false;
        try{String path=Uri.parse(url).getPath();String lower=path==null?"":path.toLowerCase(Locale.ROOT);return !lower.endsWith(".m3u8")&&!lower.endsWith(".mpd");}catch(RuntimeException invalid){return false;}
    }
    private static boolean isAudioUrl(String url){
        if(!isFileUrl(url))return false;
        String ext=extension(url,"");return !List.of("jpg","jpeg","png","webp","gif","heic").contains(ext);
    }
    private static String extension(String url,String fallback){
        try{String path=Uri.parse(url).getPath();if(path!=null){int dot=path.lastIndexOf('.');if(dot>=0){String ext=path.substring(dot+1).toLowerCase(Locale.ROOT);if(List.of("mp3","m4a","aac","opus","ogg","wav","flac","jpg","jpeg","png","webp","gif","heic").contains(ext))return ext;}}}catch(RuntimeException ignored){}
        return fallback;
    }
    private static String imageMime(String ext){return ext.equals("png")?"image/png":ext.equals("webp")?"image/webp":ext.equals("gif")?"image/gif":ext.equals("heic")?"image/heic":"image/jpeg";}
    private static String audioMime(String ext){switch(ext){case "mp3":return "audio/mpeg";case "m4a":return "audio/mp4";case "aac":return "audio/aac";case "opus":return "audio/opus";case "ogg":return "audio/ogg";case "wav":return "audio/wav";case "flac":return "audio/flac";default:return "application/octet-stream";}}
}
