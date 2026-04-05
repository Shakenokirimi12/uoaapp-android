package com.shakenokirimi12.uoa_app.data.models;

import java.util.ArrayList;
import java.util.List;

public class CourseSection {
    private int id;
    private String name;
    private String summary;
    private List<CourseModule> modules = new ArrayList<>();

    public int getId() { return id; }
    public String getName() { return name != null ? name : ""; }
    public String getSummary() { return summary != null ? summary.replaceAll("<[^>]+>", "").trim() : ""; }
    public List<CourseModule> getModules() { return modules; }

    public static class CourseModule {
        private int id;
        private String name;
        private String modname;
        private String url;

        public int getId() { return id; }
        public String getName() { return name != null ? name : ""; }
        public String getModname() { return modname != null ? modname : ""; }
        public String getUrl() { return url != null ? url : ""; }
    }
}
