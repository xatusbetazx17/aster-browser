package io.aster.android;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.speech.tts.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import io.aster.engine.*;
import java.util.*;

/** Native selection, document notes, basic forms and installed offline voices. */
final class ReadingTools {
    private final Activity activity;private final SharedPreferences prefs;
    private TextToSpeech speech;private boolean speechReady;private String queuedText,queuedLanguage;
    ReadingTools(Activity activity,SharedPreferences prefs){this.activity=activity;this.prefs=prefs;}
    void close(){if(speech!=null){speech.stop();speech.shutdown();speech=null;}speechReady=false;}
    void reader(String title,String text,String identity){
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(16,12,16,12);
        EditText search=new EditText(activity);search.setSingleLine(true);search.setHint("Find in document");content.addView(search);
        TextView body=new TextView(activity);body.setText(text);body.setTextSize(18);body.setTextIsSelectable(true);body.setPadding(12,12,12,12);
        ScrollView scroll=new ScrollView(activity);scroll.addView(body);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        search.setOnEditorActionListener((v,a,e)->{String q=search.getText().toString();if(q.isEmpty())return true;
            android.text.SpannableString highlighted=new android.text.SpannableString(text);int first=-1,count=0;
            java.util.regex.Matcher matches=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(q),java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CASE).matcher(text);
            while(matches.find()&&count++<1000){int at=matches.start(),end=matches.end();if(first<0)first=at;
                highlighted.setSpan(new android.text.style.BackgroundColorSpan(0xffffe38a),at,end,0);
                highlighted.setSpan(new android.text.style.ForegroundColorSpan(0xff173d38),at,end,0);}
            body.setText(highlighted);if(first>=0&&body.getLayout()!=null){int line=body.getLayout().getLineForOffset(first);scroll.smoothScrollTo(0,body.getLayout().getLineTop(line));}
            return true;});
        String key="note-"+java.util.UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        EditText notes=new EditText(activity);notes.setHint("Your notes");notes.setText(prefs.getString(key,""));notes.setMaxLines(3);content.addView(notes);
        Button save=new Button(activity);save.setText("Save notes");save.setOnClickListener(v->{if(notes.getText().length()>6000){Toast.makeText(activity,"Notes are limited to 6,000 characters.",Toast.LENGTH_SHORT).show();return;}prefs.edit().putString(key,notes.getText().toString()).apply();Toast.makeText(activity,"Notes saved",Toast.LENGTH_SHORT).show();});content.addView(save);
        LinearLayout voices=new LinearLayout(activity);
        for(String language:new String[]{"en","es"}){Button read=new Button(activity);read.setText(language.equals("en")?"Read English":"Leer español");read.setOnClickListener(v->speak(text,language));voices.addView(read,new LinearLayout.LayoutParams(0,-2,1));}
        Button stop=new Button(activity);stop.setText("Stop");stop.setOnClickListener(v->{if(speech!=null)speech.stop();});voices.addView(stop);content.addView(voices);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(title).setView(content).setNegativeButton("Close",null).create();
        dialog.setOnDismissListener(d->{if(speech!=null)speech.stop();});dialog.show();dialog.getWindow().setLayout(-1,-1);
    }
    private void speak(String text,String language){
        queuedText=text.length()>16000?text.substring(0,16000):text;queuedLanguage=language;
        if(speech==null){speech=new TextToSpeech(activity,code->{speechReady=code==TextToSpeech.SUCCESS;if(speechReady)say();else Toast.makeText(activity,"Install an offline system voice to read aloud.",Toast.LENGTH_LONG).show();});}
        else if(speechReady)say();
    }
    private void say(){
        if(speech==null||speech.getVoices()==null)return;
        for(Voice voice:speech.getVoices())if(!voice.isNetworkConnectionRequired()&&voice.getLocale().getLanguage().equals(queuedLanguage)){
            speech.setVoice(voice);String text=queuedText;int chunk=Math.min(3000,TextToSpeech.getMaxSpeechInputLength());
            for(int i=0;i<text.length();i+=chunk)speech.speak(text.substring(i,Math.min(text.length(),i+chunk)),i==0?TextToSpeech.QUEUE_FLUSH:TextToSpeech.QUEUE_ADD,new Bundle(),"aster-"+i);return;
        }
        Toast.makeText(activity,"Install an offline "+queuedLanguage+" voice in Android speech settings.",Toast.LENGTH_LONG).show();
    }
    void forms(java.net.URI uri,String source,java.util.function.Consumer<PageForms.Submission> submit){
        List<PageForms.Form> forms=PageForms.parse(uri,source);if(forms.isEmpty()){Toast.makeText(activity,"No supported HTML forms on this page.",Toast.LENGTH_SHORT).show();return;}
        if(forms.size()==1)form(forms.get(0),submit);else new AlertDialog.Builder(activity).setTitle("Choose a form").setItems(forms.stream().map(f->f.title).toArray(String[]::new),(d,w)->form(forms.get(w),submit)).setNegativeButton("Cancel",null).show();
    }
    private void form(PageForms.Form form,java.util.function.Consumer<PageForms.Submission> submit){
        if(!form.unsupported.isEmpty()){new AlertDialog.Builder(activity).setMessage(form.unsupported).setPositiveButton("OK",null).show();return;}
        LinearLayout fields=new LinearLayout(activity);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(20,12,20,12);
        TextView destination=new TextView(activity);destination.setText(form.method+" to "+form.action);fields.addView(destination);
        List<View> controls=new ArrayList<>();
        for(PageForms.Field f:form.fields){
            if(f.type.equals("hidden")||f.type.equals("submit")){controls.add(null);continue;}
            TextView label=new TextView(activity);label.setText(f.label+(f.required?" *":""));fields.addView(label);
            View control;
            if(f.type.equals("checkbox")){CheckBox box=new CheckBox(activity);box.setChecked(f.checked);control=box;}
            else{EditText entry=new EditText(activity);entry.setText(f.value);entry.setSingleLine(!f.type.equals("textarea"));
                entry.setInputType(InputType.TYPE_CLASS_TEXT|(f.type.equals("password")?InputType.TYPE_TEXT_VARIATION_PASSWORD:f.type.equals("textarea")?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));control=entry;}
            control.setContentDescription(f.label);controls.add(control);fields.addView(control);
        }
        ScrollView scroll=new ScrollView(activity);scroll.addView(fields);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(form.title).setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Submit",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            List<String> values=new ArrayList<>();for(int i=0;i<form.fields.size();i++){PageForms.Field f=form.fields.get(i);View c=controls.get(i);
                values.add(c==null?f.value:c instanceof CheckBox?((CheckBox)c).isChecked()?f.value:null:((EditText)c).getText().toString());}
            try{PageForms.Submission request=form.submit(values);dialog.dismiss();submit.accept(request);}catch(IllegalArgumentException e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();}
        }));dialog.show();
    }
}
