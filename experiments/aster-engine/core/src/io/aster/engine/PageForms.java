package io.aster.engine;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native GET/POST forms shared by desktop and Android. No script-generated fields. */
public final class PageForms {
    public static final class Field {
        public final String name, value, type, label;
        public final boolean checked, required;
        Field(Map<String,String> a,String type,String value){name=a.getOrDefault("name","");this.type=type;this.value=value;
            label=a.getOrDefault("aria-label",a.getOrDefault("placeholder",name));checked=a.containsKey("checked");required=a.containsKey("required");}
    }
    public static final class Form {
        public final URI page, action; public final String method, title;
        public final List<Field> fields=new ArrayList<>(); public String unsupported="";
        Form(URI page,Map<String,String> a,int number){this.page=page;
            action=PageLoader.link(page,a.getOrDefault("action",page.toString()));method=a.getOrDefault("method","get").toUpperCase(Locale.ROOT);
            title=a.getOrDefault("aria-label","Form "+number);
            if(!PageAssets.sameOrigin(page,action))unsupported="Cross-origin form actions are not supported yet.";
            if(!method.equals("GET")&&!method.equals("POST"))unsupported="This form method is unsupported.";
            if(!a.getOrDefault("enctype","application/x-www-form-urlencoded").equalsIgnoreCase("application/x-www-form-urlencoded"))unsupported="File and multipart forms are not supported yet.";
        }
        public Submission submit(List<String> values) {
            if(!unsupported.isEmpty())throw new IllegalArgumentException(unsupported);
            if(values.size()!=fields.size())throw new IllegalArgumentException("Form changed; reopen it.");
            StringJoiner encoded=new StringJoiner("&");boolean submitted=false;
            for(int i=0;i<fields.size();i++){
                Field f=fields.get(i);String value=values.get(i);
                if(f.required&&value==null)throw new IllegalArgumentException("Complete "+f.label+".");
                if(f.name.isEmpty()||value==null)continue;
                if(f.required&&value.trim().isEmpty())throw new IllegalArgumentException("Complete "+f.label+".");
                if(f.type.equals("password")&&!"https".equalsIgnoreCase(action.getScheme()))throw new IllegalArgumentException("Password forms require HTTPS.");
                if(f.type.equals("submit")){if(submitted)continue;submitted=true;}
                if(value.length()>8192)throw new IllegalArgumentException("Form field is too long.");
                encoded.add(encode(f.name)+"="+encode(value));
            }
            byte[] body=encoded.toString().getBytes(StandardCharsets.UTF_8);
            if(body.length>65536)throw new IllegalArgumentException("Form exceeds 64 KiB.");
            String base=action.toString().split("#",2)[0];
            return method.equals("GET")?new Submission(URI.create(base.split("\\?",2)[0]+"?"+encoded),null):new Submission(URI.create(base),body);
        }
    }
    public static final class Submission {
        public final URI uri;public final byte[] body;
        Submission(URI uri,byte[] body){this.uri=uri;this.body=body;}
    }
    public static List<Form> parse(URI page,String source) {
        List<Form> result=new ArrayList<>();Form current=null;
        for(PageMarkup.Tag t:PageMarkup.tags(source)){
            if(t.name.equals("form")){
                if(t.closing){current=null;continue;}
                if(result.size()>=8)break;current=new Form(page,t.attrs,result.size()+1);result.add(current);continue;
            }
            if(current==null||t.closing)continue;
            if(t.name.equals("fieldset")&&t.attrs.containsKey("disabled"))current.unsupported="Disabled fieldsets are not supported yet.";
            if(t.name.equals("select")){current.unsupported="Select controls are not supported yet; this form cannot be submitted.";continue;}
            if(!t.name.equals("input")&&!t.name.equals("textarea")&&!t.name.equals("button"))continue;
            if(t.attrs.containsKey("disabled"))continue;
            if(current.fields.size()>=64){current.unsupported="This form has too many fields.";continue;}
            String type=t.name.equals("textarea")?"textarea":t.attrs.getOrDefault("type",t.name.equals("button")?"submit":"text").toLowerCase(Locale.ROOT);
            if(type.equals("button")||type.equals("reset"))continue;
            if(!Arrays.asList("text","search","email","url","tel","number","password","hidden","checkbox","textarea","submit").contains(type)) {
                current.unsupported="This form contains an unsupported "+type+" control.";continue;
            }
            if(t.attrs.containsKey("form")||t.attrs.containsKey("formaction")||t.attrs.containsKey("formmethod"))current.unsupported="Form override attributes are not supported yet.";
            String value=t.name.equals("textarea")?Engine.entities(t.text):t.attrs.getOrDefault("value",type.equals("checkbox")?"on":"");
            current.fields.add(new Field(t.attrs,type,value));
        }
        return result;
    }
    private static String encode(String value){try{return URLEncoder.encode(value,"UTF-8");}catch(Exception impossible){throw new AssertionError(impossible);}}
    private PageForms() { }
}
