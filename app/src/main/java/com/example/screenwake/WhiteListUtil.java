package com.example.screenwake;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WhiteListUtil {
    private static final String WHITELIST_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/ScreenWakeLogs/whitelist/";
    private static final String FILE_ACTION = WHITELIST_DIR + "action_whitelist.txt";
    private static final String FILE_SERVER_ALLOW = WHITELIST_DIR + "server_allow.txt";
    private static final String FILE_TRUST_BIND = WHITELIST_DIR + "server_trust.txt";

    public static Set<String> readLines(String path){
        Set<String> out = new HashSet<>();
        File f = new File(path);
        if(!f.exists()) return out;
        try{
            List<String> lines = Files.readAllLines(f.toPath());
            for(String l : lines){
                l = l.trim();
                if(l.isEmpty() || l.startsWith("#")) continue;
                out.add(l);
            }
        }catch (Exception e){
            Log.e("WhiteList","read err",e);
        }
        return out;
    }
    public static boolean isServerIpAllowed(Context ctx, String host){
        return readLines(FILE_SERVER_ALLOW).contains(host);
    }
    public static boolean isActionAllowed(String action){
        return readLines(FILE_ACTION).contains(action);
    }
    public static boolean isBindTrusted(Context ctx, String bindId){
        return readLines(FILE_TRUST_BIND).contains(bindId);
    }
    public static void saveTrustedBind(Context ctx, String bindId){
        File f = new File(FILE_TRUST_BIND);
        try{
            Files.write(f.toPath(), (bindId+"\n").getBytes());
        }catch (Exception e){
            Log.e("WhiteList","write bind err",e);
        }
    }
}
