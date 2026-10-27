package com.goldcatcher;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String,String>> method(org.springframework.web.HttpRequestMethodNotSupportedException failure) {
        return ResponseEntity.status(405).allow(failure.getSupportedHttpMethods()==null?new HttpMethod[0]:failure.getSupportedHttpMethods().toArray(HttpMethod[]::new)).body(Map.of("error","METHOD_NOT_ALLOWED"));
    }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
    ResponseEntity<Map<String,String>> known(RuntimeException failure) {
        String code=failure.getMessage();if(code==null||!code.matches("[A-Z0-9_]+"))code="INVALID_REQUEST_OR_STATE";
        return ResponseEntity.status(failure instanceof IllegalArgumentException?400:409).body(Map.of("error",code));
    }
}
