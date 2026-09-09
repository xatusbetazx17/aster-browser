package io.aster.desktop;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** A pinned reading workspace: navigating a tab never replaces unfinished notes. */
final class ReaderDock extends JPanel {
    final JTextArea content=new JTextArea(), notes=new JTextArea(4,20);
    final JTextField query=new JTextField(12);
    private final JLabel title=new JLabel(), feedback=new JLabel("Notes stay on this device.");
    private final Preferences preferences;
    private final Timer save;
    private final JSplitPane sections;
    private String identity="", text="";
    private boolean updating;
    private int sectionHeight=-1;
    private volatile boolean active;
    ReaderDock(Preferences preferences,Runnable close) {
        super(new BorderLayout(0,10));this.preferences=preferences;
        setBorder(BorderFactory.createEmptyBorder(12,14,12,14));setMinimumSize(new Dimension(280,180));
        JPanel heading=new JPanel(new BorderLayout(8,0));title.putClientProperty("html.disable",Boolean.TRUE);
        title.setFont(new Font(Font.SANS_SERIF,Font.BOLD,16));heading.add(title);
        JButton dismiss=button("Close","Close reading panel",close);heading.add(dismiss,BorderLayout.EAST);
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));top.add(heading);
        JPanel search=new JPanel(new BorderLayout(6,0));query.getAccessibleContext().setAccessibleName("Find in reading panel");search.add(query);
        JButton next=button("Find next","Find next in reading panel",this::findNext);query.addActionListener(e->findNext());search.add(next,BorderLayout.EAST);top.add(search);
        JPanel speech=new JPanel(new FlowLayout(FlowLayout.LEFT,6,2));JComboBox<String> language=new JComboBox<>(new String[]{"English","Español"});
        language.getAccessibleContext().setAccessibleName("Read aloud language");speech.add(language);
        speech.add(button("Read aloud","Read selected text or continue from the cursor",()->{
            String selection=content.getSelectedText(),passage=selection==null?text.substring(Math.min(content.getCaretPosition(),text.length())):selection;
            String code=language.getSelectedIndex()==0?"en":"es";
            Thread worker=new Thread(()->{synchronized(ReaderDock.this){if(!active)return;try{ReadingTools.speak(passage,code);}catch(Exception error){SwingUtilities.invokeLater(()->feedback.setText("Install an offline system voice to read aloud."));}}},"aster-reader-voice");worker.setDaemon(true);worker.start();
        }));speech.add(button("Stop","Stop reading aloud",ReadingTools::stopSpeech));top.add(speech);add(top,BorderLayout.NORTH);
        content.setEditable(false);content.setLineWrap(true);content.setWrapStyleWord(true);content.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,18));content.setMargin(new Insets(12,14,12,14));content.getAccessibleContext().setAccessibleName("Reading text");
        notes.setLineWrap(true);notes.setWrapStyleWord(true);notes.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15));notes.setMargin(new Insets(8,10,8,10));notes.getAccessibleContext().setAccessibleName("Notes for this page or document");
        JPanel bottom=new JPanel(new BorderLayout(0,4));bottom.add(new JLabel("Your notes · saved automatically"),BorderLayout.NORTH);bottom.add(new JScrollPane(notes));bottom.add(feedback,BorderLayout.SOUTH);
        JScrollPane reading=new JScrollPane(content);reading.setMinimumSize(new Dimension(0,48));bottom.setMinimumSize(new Dimension(0,92));
        sections=new JSplitPane(JSplitPane.VERTICAL_SPLIT,reading,bottom);sections.setResizeWeight(.62);sections.setDividerSize(5);sections.setBorder(BorderFactory.createEmptyBorder());add(sections);
        save=new Timer(400,e->flush());save.setRepeats(false);
        ((AbstractDocument)notes.getDocument()).setDocumentFilter(new DocumentFilter(){
            public void insertString(FilterBypass b,int at,String s,AttributeSet a)throws BadLocationException{replace(b,at,0,s,a);}
            public void replace(FilterBypass b,int at,int length,String s,AttributeSet a)throws BadLocationException{
                String old=b.getDocument().getText(0,b.getDocument().getLength());String next=old.substring(0,at)+(s==null?"":s)+old.substring(at+length);
                if(next.getBytes(StandardCharsets.UTF_8).length>6000){feedback.setText("This note is full (6 KB). Copy it before adding more.");return;}b.replace(at,length,s,a);
            }
        });
        notes.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}private void changed(){if(!updating){feedback.setText("Saving notes…");save.restart();}}});
        WorkspaceTheme.apply(this);title.setFont(new Font(Font.SANS_SERIF,Font.BOLD,16));
    }
    public void doLayout(){super.doLayout();int available=sections.getHeight()-sections.getDividerSize();if(sectionHeight!=available){sectionHeight=available;sections.setDividerLocation(Math.max(48,Math.min(available-92,(int)(available*.60))));}}
    private static JButton button(String label,String name,Runnable action){JButton b=new JButton(label);b.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14));b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkspaceTheme.LINE),BorderFactory.createEmptyBorder(4,6,4,6)));b.getAccessibleContext().setAccessibleName(name);b.addActionListener(e->action.run());return b;}
    synchronized void showDocument(String heading,String body,String id){flush();ReadingTools.stopSpeech();identity=id;text=body;title.setText(heading);title.setToolTipText("\u200b"+heading);active=true;
        content.setText(body);content.setCaretPosition(Math.min(body.length(),Math.max(0,preferences.getInt(positionKey(),0))));query.setText("");
        updating=true;try{notes.setText(preferences.get(ReadingTools.noteKey(id),""));notes.setCaretPosition(0);}finally{updating=false;}feedback.setText("Notes stay on this device.");
    }
    void flush(){save.stop();if(identity.isEmpty())return;preferences.put(ReadingTools.noteKey(identity),notes.getText());preferences.putInt(positionKey(),content.getCaretPosition());feedback.setText("Notes saved on this device.");}
    private String positionKey(){return "read-"+ReadingTools.noteKey(identity).substring(5);}
    synchronized void close(){active=false;flush();ReadingTools.stopSpeech();}
    void findNext(){String term=query.getText().toLowerCase(Locale.ROOT);if(term.isEmpty())return;String lower=text.toLowerCase(Locale.ROOT);int at=lower.indexOf(term,content.getSelectionEnd());if(at<0)at=lower.indexOf(term);feedback.setText(at<0?"No match":"Match found");if(at>=0){content.requestFocusInWindow();content.select(at,Math.min(text.length(),at+query.getText().length()));}}
}
