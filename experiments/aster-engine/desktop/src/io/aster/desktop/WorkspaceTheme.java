package io.aster.desktop;

import java.awt.*;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Aster chrome only: website colors are always owned by the page renderer. */
final class WorkspaceTheme {
    // Port the cyan/dark presentation from new-development onto native controls.
    static final Color TEXT=new Color(0xe2e8f0), ACCENT=new Color(0x67cbef), PAGE=new Color(0x131720);
    static final Color CHROME=new Color(0x10131a), RAISED=new Color(0x1a202c), LINE=new Color(0x2a3344), MUTED=new Color(0xa5b4c6), HOVER=new Color(0x252d3d);
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
