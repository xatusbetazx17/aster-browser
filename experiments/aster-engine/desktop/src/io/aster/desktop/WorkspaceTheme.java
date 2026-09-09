package io.aster.desktop;

import java.awt.*;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Aster chrome only: website colors are always owned by the page renderer. */
final class WorkspaceTheme {
    static final Color TEXT=new Color(0xe8eef5), ACCENT=new Color(0x64dfce), PAGE=new Color(0x121922);
    static final Color CHROME=new Color(0x0b1017), RAISED=new Color(0x1b2633), LINE=new Color(0x304254), MUTED=new Color(0xa5b4c6);
    static void apply(Component component) {
        if(component instanceof PreviewMain.PageCanvas || component instanceof MediaPanel)return;
        component.setForeground(component instanceof JComponent && Boolean.TRUE.equals(((JComponent)component).getClientProperty("aster.accent"))?ACCENT:TEXT);
        if(component instanceof JTextComponent){JTextComponent text=(JTextComponent)component;text.setCaretColor(TEXT);text.setSelectionColor(LINE);text.setSelectedTextColor(TEXT);text.setBackground(RAISED);}
        else if(component instanceof AbstractButton || component instanceof JComboBox)component.setBackground(RAISED);
        else component.setBackground(PAGE);
        if((component instanceof JLabel || component instanceof JCheckBox) && component.getFont().getSize()<13)component.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14));
        if(component instanceof Container)for(Component child:((Container)component).getComponents())apply(child);
    }
    private WorkspaceTheme() { }
}
