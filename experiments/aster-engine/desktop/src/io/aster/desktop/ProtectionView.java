package io.aster.desktop;

import io.aster.engine.*;
import java.awt.*;
import java.net.URI;
import javax.swing.*;

/** Native controls; page scripts cannot read activity or change blocking rules. */
final class ProtectionView extends JPanel {
    final JCheckBox enabled=new JCheckBox("Block ads and trackers");
    final JCheckBox exception=new JCheckBox("Allow requests on this site");
    final JTextArea rules=new JTextArea(5,32),activity=new JTextArea(5,32);
    final JLabel result=new JLabel("Changes apply to new requests. Reload the page to retry its resources.");
    ProtectionView(Protection protection,URI page,Runnable persist){
        super(new BorderLayout(0,12));setBorder(BorderFactory.createEmptyBorder(18,20,18,20));
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JLabel title=new JLabel("Site protection");title.setFont(new Font(Font.SANS_SERIF,Font.BOLD,24));top.add(title);top.add(Box.createVerticalStrut(12));
        JTextArea explanation=new JTextArea("An offline starter list blocks "+protection.starterCount()+" ad/tracker hostnames and their subdomains. Add your own hostnames below. Direct navigation and downloads remain available.",3,34);explanation.setLineWrap(true);explanation.setWrapStyleWord(true);explanation.setEditable(false);explanation.setOpaque(false);top.add(explanation);
        enabled.setSelected(protection.enabled());enabled.addActionListener(e->{protection.enabled(enabled.isSelected());persist.run();});top.add(enabled);
        String origin=SiteData.origin(page);exception.setSelected(protection.allowed(page));exception.setEnabled(!origin.isEmpty());
        exception.setToolTipText(origin.isEmpty()?"Open a website to add an exception":origin);exception.addActionListener(e->{try{protection.allow(page,exception.isSelected());persist.run();}catch(IllegalArgumentException error){exception.setSelected(protection.allowed(page));result.setText(error.getMessage());}});top.add(exception);
        JLabel scope=new JLabel(origin.isEmpty()?"No website selected":origin);scope.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));top.add(scope);add(top,BorderLayout.NORTH);
        JPanel middle=new JPanel();middle.setLayout(new BoxLayout(middle,BoxLayout.Y_AXIS));
        middle.add(new JLabel("Blocked requests · this session"));activity.setEditable(false);activity.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));middle.add(new JScrollPane(activity));
        Runnable refresh=()->{Protection.Snapshot snapshot=protection.snapshot(page);StringBuilder text=new StringBuilder(snapshot.blocked+" requests blocked for this origin.\n");snapshot.hosts.forEach((host,count)->text.append(count).append("  ").append(host).append('\n'));if(snapshot.hosts.isEmpty())text.append("No blocked requests recorded.\n");activity.setText(text.toString());activity.setCaretPosition(0);};refresh.run();
        JButton refreshButton=new JButton("Refresh activity");refreshButton.addActionListener(e->refresh.run());middle.add(refreshButton);middle.add(Box.createVerticalStrut(12));
        middle.add(new JLabel("Your blocked hostnames · one per line"));rules.setText(protection.custom());rules.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));rules.getAccessibleContext().setAccessibleName("Custom blocked hostnames");middle.add(new JScrollPane(rules));
        JButton save=new JButton("Save hostname rules");save.addActionListener(e->{try{protection.custom(rules.getText());persist.run();rules.setText(protection.custom());result.setText("Rules applied. Reload a website to retry its resources.");}catch(IllegalArgumentException error){result.setText(error.getMessage());}});middle.add(save);add(middle,BorderLayout.CENTER);
        result.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,12));add(result,BorderLayout.SOUTH);WorkspaceTheme.apply(this);
    }
}
