package io.aster.desktop;

import io.aster.engine.*;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.prefs.Preferences;

/** Native reading, selection, document notes and explicitly requested system speech. */
final class ReadingTools {
    private static Process speech;
    static void stopSpeech(){if(speech!=null){speech.destroyForcibly();speech=null;}}
    static void open(Component owner,String title,String text,String identity,Preferences preferences) {
        JDialog dialog=new JDialog((Frame)SwingUtilities.getWindowAncestor(owner),title,false);
        JTextArea content=new JTextArea(text);content.setEditable(false);content.setLineWrap(true);content.setWrapStyleWord(true);
        content.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,18));content.setMargin(new Insets(16,20,16,20));content.setCaretPosition(0);
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.LEFT));JTextField query=new JTextField(18);tools.add(new JLabel("Find"));tools.add(query);
        JLabel matches=new JLabel(" ");tools.add(matches);
        JButton find=new JButton("Next");Runnable search=()->{
            String term=query.getText().toLowerCase(Locale.ROOT),lower=text.toLowerCase(Locale.ROOT);
            if(term.isEmpty())return;int at=lower.indexOf(term,content.getSelectionEnd());if(at<0)at=lower.indexOf(term);
            matches.setText(at<0?"No match":"Match found");if(at>=0){content.requestFocusInWindow();content.select(at,at+term.length());}
        };find.addActionListener(e->search.run());query.addActionListener(e->search.run());tools.add(find);
        for(String language:new String[]{"English","Spanish"}){JButton speak=new JButton("Read aloud · "+language);speak.addActionListener(e->{
            String selected=content.getSelectedText();String passage=selected==null?text.substring(content.getCaretPosition()):selected;
            new Thread(()->{try{speak(passage,language.equals("English")?"en":"es");}catch(Exception error){SwingUtilities.invokeLater(()->matches.setText("Speech unavailable: install an offline system voice."));}},"aster-read-aloud").start();
        });tools.add(speak);}
        JButton stop=new JButton("Stop");stop.addActionListener(e->stopSpeech());tools.add(stop);
        String key=noteKey(identity);JTextArea notes=new JTextArea(preferences.get(key,""),4,30);notes.setLineWrap(true);notes.setWrapStyleWord(true);
        JPanel bottom=new JPanel(new BorderLayout());bottom.add(new JLabel("Your notes"),BorderLayout.NORTH);bottom.add(new JScrollPane(notes));
        JButton save=new JButton("Save notes");save.addActionListener(e->{if(notes.getText().getBytes(StandardCharsets.UTF_8).length>6000){matches.setText("Notes are limited to 6 KB per document.");return;}preferences.put(key,notes.getText());matches.setText("Notes saved on this device.");});bottom.add(save,BorderLayout.EAST);
        dialog.add(tools,BorderLayout.NORTH);dialog.add(new JScrollPane(content));dialog.add(bottom,BorderLayout.SOUTH);
        dialog.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){stopSpeech();}});
        dialog.setSize(950,700);dialog.setLocationRelativeTo(owner);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setVisible(true);
    }
    static String noteKey(String identity){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder("note-");for(byte v:b)out.append(String.format("%02x",v&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private static synchronized void speak(String text,String language)throws Exception{
        stopSpeech();if(text.isEmpty())return;
        if(text.length()>16000)text=text.substring(0,16000);
        ProcessBuilder builder;
        if(System.getProperty("os.name").startsWith("Windows")){
            String command="$ErrorActionPreference='Stop';[Console]::InputEncoding=[System.Text.Encoding]::UTF8;Add-Type -AssemblyName System.Speech;"+
                "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"+
                "$v=$s.GetInstalledVoices()|Where-Object {$_.Enabled -and $_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq '"+language+"'}|Select-Object -First 1;"+
                "if(!$v){exit 2};$s.SelectVoice($v.VoiceInfo.Name);$s.Speak([Console]::In.ReadToEnd());$s.Dispose()";
            String encoded=Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
            String system=System.getenv("SystemRoot");if(system==null)throw new IOException("Windows system path missing");
            builder=new ProcessBuilder(Paths.get(system,"System32","WindowsPowerShell","v1.0","powershell.exe").toString(),"-NoProfile","-NonInteractive","-EncodedCommand",encoded);
        }else{
            Path voice=Paths.get("/usr/bin/espeak-ng");if(!Files.isExecutable(voice))voice=Paths.get("/usr/bin/espeak");
            if(!Files.isExecutable(voice))throw new IOException("Offline speech engine missing");builder=new ProcessBuilder(voice.toString(),"--stdin","-v",language);
        }
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);builder.redirectError(ProcessBuilder.Redirect.DISCARD);speech=builder.start();
        try(Writer writer=new OutputStreamWriter(speech.getOutputStream(),StandardCharsets.UTF_8)){writer.write(text);}
    }
    static java.util.List<String> form(Component owner,PageForms.Form form){
        if(!form.unsupported.isEmpty()){JOptionPane.showMessageDialog(owner,form.unsupported);return null;}
        JPanel panel=new JPanel();panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));
        JTextArea destination=new JTextArea(form.method+" to "+form.action);destination.setEditable(false);destination.setLineWrap(true);destination.setWrapStyleWord(true);panel.add(destination);
        java.util.List<JComponent> controls=new ArrayList<>();
        for(PageForms.Field field:form.fields){
            JComponent control;
            if(field.type.equals("hidden")||field.type.equals("submit")){controls.add(null);continue;}
            JLabel label=new JLabel();label.putClientProperty("html.disable",Boolean.TRUE);label.setText(field.label+(field.required?" *":""));panel.add(label);
            if(field.type.equals("checkbox")){JCheckBox box=new JCheckBox();box.setSelected(field.checked);control=box;}
            else if(field.type.equals("password"))control=new JPasswordField(field.value,25);
            else if(field.type.equals("textarea"))control=new JTextArea(field.value,3,25);
            else control=new JTextField(field.value,25);
            control.getAccessibleContext().setAccessibleName(field.label);controls.add(control);panel.add(control);
        }
        JScrollPane scroller=new JScrollPane(panel);scroller.setPreferredSize(new Dimension(480,Math.min(550,120+form.fields.size()*58)));
        if(JOptionPane.showConfirmDialog(owner,scroller,form.title,JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return null;
        java.util.List<String> values=new ArrayList<>();
        for(int i=0;i<form.fields.size();i++){PageForms.Field f=form.fields.get(i);JComponent c=controls.get(i);
            values.add(c==null?f.value:c instanceof JCheckBox?((JCheckBox)c).isSelected()?f.value:null:((JTextComponent)c).getText());}
        return values;
    }
    private ReadingTools() { }
}
