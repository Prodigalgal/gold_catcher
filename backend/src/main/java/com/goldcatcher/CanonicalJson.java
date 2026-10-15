package com.goldcatcher;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import tools.jackson.databind.*;

/** Stable object ordering; arrays, nulls, strings and financial decimals retain their meaning. */
public final class CanonicalJson {
    private CanonicalJson() {}
    public static JsonNode tree(ObjectMapper mapper,JsonNode value){
        if(value.isObject()){var out=mapper.createObjectNode();value.propertyNames().stream().sorted().forEach(k->out.set(k,tree(mapper,value.path(k))));return out;}
        if(value.isArray()){var out=mapper.createArrayNode();value.forEach(v->out.add(tree(mapper,v)));return out;}
        return value.deepCopy();
    }
    public static String text(ObjectMapper mapper,Object value){return mapper.writeValueAsString(tree(mapper,mapper.valueToTree(value)));}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
