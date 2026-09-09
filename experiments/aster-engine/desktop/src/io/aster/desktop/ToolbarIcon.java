package io.aster.desktop;

import java.awt.*;
import java.awt.geom.Path2D;
import javax.swing.*;

/** Small vector controls, independent of the installed font's symbol coverage. */
final class ToolbarIcon implements Icon {
    private final String kind;
    ToolbarIcon(String kind){this.kind=kind;}
    public int getIconWidth(){return 16;}public int getIconHeight(){return 16;}
    public void paintIcon(Component c,Graphics graphics,int x,int y){
        Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(c.isEnabled()?WorkspaceTheme.TEXT:WorkspaceTheme.MUTED);g.setStroke(new BasicStroke(1.4f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        switch(kind){
            case "←":case "→":if(kind.equals("→")){g.translate(16,0);g.scale(-1,1);}g.drawLine(3,8,13,8);g.drawLine(3,8,7,4);g.drawLine(3,8,7,12);break;
            case "⌂":{Path2D p=new Path2D.Float();p.moveTo(2,7);p.lineTo(8,2);p.lineTo(14,7);p.moveTo(4,6);p.lineTo(4,14);p.lineTo(12,14);p.lineTo(12,6);p.moveTo(7,14);p.lineTo(7,10);p.lineTo(9,10);p.lineTo(9,14);g.draw(p);break;}
            case "↻":g.drawArc(2,2,12,12,40,285);g.drawLine(13,2,13,6);g.drawLine(13,6,9,6);break;
            case "×":g.drawLine(4,4,12,12);g.drawLine(4,12,12,4);break;
            case "☆":{Path2D p=new Path2D.Float();for(int i=0;i<10;i++){double angle=-Math.PI/2+i*Math.PI/5;float radius=i%2==0?7:3.2f;float px=8+(float)Math.cos(angle)*radius,py=8+(float)Math.sin(angle)*radius;if(i==0)p.moveTo(px,py);else p.lineTo(px,py);}p.closePath();g.draw(p);break;}
            default:break;
        }g.dispose();
    }
}
