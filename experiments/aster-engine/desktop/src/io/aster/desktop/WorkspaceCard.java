package io.aster.desktop;

import java.awt.*;
import javax.swing.*;

/** Painted native workspace card with ordinary keyboard/button semantics. */
final class WorkspaceCard extends JButton {
    private final String title,description,category;
    WorkspaceCard(String category,String title,String description,Runnable action){
        this.category=category;this.title=title;this.description=description;setText("");setContentAreaFilled(false);setBorderPainted(false);setOpaque(false);
        setPreferredSize(new Dimension(260,132));setToolTipText(description);getAccessibleContext().setAccessibleName(title);getAccessibleContext().setAccessibleDescription(description);addActionListener(e->action.run());
    }
    protected void paintComponent(Graphics graphics){
        Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(getModel().isPressed()?WorkspaceTheme.LINE:getModel().isRollover()?WorkspaceTheme.HOVER:WorkspaceTheme.RAISED);g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,22,22);
        g.setColor(hasFocus()?WorkspaceTheme.ACCENT:WorkspaceTheme.LINE);g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,22,22);
        g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,11));g.setColor(WorkspaceTheme.ACCENT);g.drawString(category,18,25);
        g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,19));g.setColor(WorkspaceTheme.TEXT);g.drawString(title,18,53);
        g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14));g.setColor(WorkspaceTheme.MUTED);FontMetrics metrics=g.getFontMetrics();String line="";int y=80;
        for(String word:description.split(" ")){String next=line.isEmpty()?word:line+" "+word;if(!line.isEmpty()&&metrics.stringWidth(next)>getWidth()-36){g.drawString(line,18,y);y+=19;line=word;}else line=next;}g.drawString(line,18,y);g.dispose();
    }
}
