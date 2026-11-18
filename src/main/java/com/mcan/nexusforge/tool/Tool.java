package com.mcan.nexusforge.tool;


import com.google.gson.JsonObject;
public interface Tool {
    String name();
    String description();
    JsonObject parameters();
    String execute(JsonObject arguments) throws Exception;
}
