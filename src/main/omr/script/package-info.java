@XmlJavaTypeAdapters({
    @XmlJavaTypeAdapter(value = Jaxb.PointAdapter.class, type = Point.class),
    @XmlJavaTypeAdapter(value = Jaxb.Point2DAdapter.class, type = Point2D.class),
    @XmlJavaTypeAdapter(value = Jaxb.RectangleAdapter.class, type = Rectangle.class)
})
package omr.script;

import omr.util.Jaxb;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Point2D;

import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapters;
