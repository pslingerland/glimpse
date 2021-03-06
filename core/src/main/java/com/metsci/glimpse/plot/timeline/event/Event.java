/*
 * Copyright (c) 2012, Metron, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Metron, Inc. nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL METRON, INC. BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.metsci.glimpse.plot.timeline.event;

import java.awt.geom.Rectangle2D;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import javax.media.opengl.GL;

import com.metsci.glimpse.axis.Axis1D;
import com.metsci.glimpse.plot.timeline.data.Epoch;
import com.metsci.glimpse.plot.timeline.data.EventConstraint;
import com.metsci.glimpse.plot.timeline.data.TimeSpan;
import com.metsci.glimpse.support.atlas.TextureAtlas;
import com.metsci.glimpse.support.atlas.support.ImageData;
import com.metsci.glimpse.support.color.GlimpseColor;
import com.metsci.glimpse.util.units.time.TimeStamp;
import com.sun.opengl.util.j2d.TextRenderer;

public class Event
{
    public static final int ARROW_TIP_BUFFER = 2;
    public static final int ARROW_SIZE = 10;
    public static final float[] DEFAULT_COLOR = GlimpseColor.getGray( );

    protected EventPlotInfo info;

    protected Object id;
    protected String name;
    protected Object iconId; // references id in associated TextureAtlas
    protected String toolTipText;
    
    protected float[] backgroundColor;
    protected float[] borderColor;
    protected float[] textColor;
    protected float borderThickness = 1.8f;

    protected TimeStamp startTime;
    protected TimeStamp endTime;

    protected boolean showName = true;
    protected boolean showIcon = true;
    protected boolean showBorder = true;
    protected boolean showBackground = true;

    protected boolean hideOverfull;
    protected boolean hideIntersecting;

    protected boolean isIconVisible;
    protected boolean isTextVisible;
    protected TimeStamp iconStartTime;
    protected TimeStamp iconEndTime;
    protected TimeStamp textStartTime;
    protected TimeStamp textEndTime;

    protected boolean isEditable = true;
    protected boolean isEndTimeMoveable = true;
    protected boolean isStartTimeMoveable = true;
    protected boolean isResizeable = true;
    protected double maxTimeSpan = Double.MAX_VALUE;
    protected double minTimeSpan = 0;

    protected List<EventConstraint> constraints;

    protected EventConstraint builtInConstraints = new EventConstraint( )
    {
        @Override
        public TimeSpan applyConstraint( Event event, TimeSpan proposedTimeSpan )
        {
            if ( !isEditable ) return event.getTimeSpan( );
            
            TimeStamp oldStart = event.getStartTime( );
            TimeStamp oldEnd = event.getEndTime( );

            TimeStamp newStart = proposedTimeSpan.getStartTime( );
            TimeStamp newEnd = proposedTimeSpan.getEndTime( );

            if ( !isEndTimeMoveable ) newEnd = oldEnd;
            if ( !isStartTimeMoveable ) newStart = oldStart;

            double newDiff = newEnd.durationAfter( newStart );
            double oldDiff = oldEnd.durationAfter( oldStart );

            if ( !isResizeable && newDiff != oldDiff )
            {
                newEnd = oldEnd;
                newStart = oldStart;
            }

            if ( newDiff < minTimeSpan )
            {
                if ( oldEnd.equals( newEnd ) )
                {
                    newStart = newEnd.subtract( minTimeSpan );
                }
                else
                {
                    newEnd = newStart.add( minTimeSpan );
                }
            }

            if ( newDiff > maxTimeSpan )
            {
                if ( oldEnd.equals( newEnd ) )
                {
                    newStart = newEnd.subtract( maxTimeSpan );
                }
                else
                {
                    newEnd = newStart.add( maxTimeSpan );
                }
            }

            return new TimeSpan( newStart, newEnd );
        }
    };

    private Event( TimeStamp time )
    {
        this( null, null, time );
    }

    public Event( Object id, String name, TimeStamp time )
    {
        this.id = id;
        this.name = name;
        this.startTime = time;
        this.endTime = time;

        this.hideIntersecting = true;
        this.hideOverfull = false;

        this.constraints = new LinkedList<EventConstraint>( );
        this.constraints.add( builtInConstraints );
    }

    public Event( Object id, String name, TimeStamp startTime, TimeStamp endTime )
    {
        this.id = id;
        this.name = name;
        this.startTime = startTime;
        this.endTime = endTime;

        this.hideIntersecting = true;
        this.hideOverfull = true;

        this.constraints = new LinkedList<EventConstraint>( );
        this.constraints.add( builtInConstraints );
    }

    /**
     * <p>Adds an EventConstraint which determines whether proposed changes to the min
     * and max time bounds of an Event are allowed.</p>
     * 
     * <p>This method should be used for specialized constraints. Events support basic
     * constraints by default via setEditable, setResizeable, setEndTimeMoveable,
     * setStartTimeMoveable, setMinTimeSpan, and setMaxTimeSpan.</p>
     * 
     * @param constraint
     */
    public void addConstraint( EventConstraint constraint )
    {
        this.constraints.add( constraint );
    }

    public void removeConstrain( EventConstraint constraint )
    {
        this.constraints.remove( constraint );
    }

    public void paint( GL gl, Axis1D axis, EventPainter painter, Event next, int width, int height, int sizeMin, int sizeMax )
    {
        int size = sizeMax - sizeMin;
        double sizeCenter = sizeMin + size / 2.0;
        int buffer = painter.getBufferSize( );
        int arrowSize = Math.min( size, ARROW_SIZE );
        
        Epoch epoch = painter.getEpoch( );
        double timeMin = epoch.fromTimeStamp( startTime );
        double timeMax = epoch.fromTimeStamp( endTime );

        double arrowBaseMin = timeMin;
        boolean offEdgeMin = false;
        if ( axis.getMin( ) > timeMin )
        {
            offEdgeMin = true;
            timeMin = axis.getMin( ) + ARROW_TIP_BUFFER / axis.getPixelsPerValue( );
            arrowBaseMin = timeMin + arrowSize / axis.getPixelsPerValue( );
        }
        
        double arrowBaseMax = timeMax;
        boolean offEdgeMax = false;
        if ( axis.getMax( ) < timeMax )
        {
            offEdgeMax = true;
            timeMax = axis.getMax( ) - ARROW_TIP_BUFFER / axis.getPixelsPerValue( );
            arrowBaseMax = timeMax - arrowSize / axis.getPixelsPerValue( );
        }
        
        arrowBaseMax = Math.max( timeMin, arrowBaseMax );
        arrowBaseMin = Math.min( timeMax, arrowBaseMin );
        
        double timeSpan = timeMax - timeMin;
        double remainingSpaceX = axis.getPixelsPerValue( ) * timeSpan - buffer * 2;

        int pixelX = buffer + ( offEdgeMin ? arrowSize : 0 ) + Math.max( 0, axis.valueToScreenPixel( timeMin ) );

        // start positions of the next event in this row
        double nextStartValue = next != null ? epoch.fromTimeStamp( next.getStartTime( ) ) : axis.getMax( );
        int nextStartPixel = next != null ? axis.valueToScreenPixel( nextStartValue ) : width;

        if ( painter.isHorizontal( ) )
        {   
            if ( !offEdgeMin && !offEdgeMax )
            {
                if ( showBackground )
                {
                    GlimpseColor.glColor( gl, backgroundColor != null ? backgroundColor : painter.getBackgroundColor( ) );
                    gl.glBegin( GL.GL_QUADS );
                    try
                    {
                        gl.glVertex2d( timeMin, sizeMin );
                        gl.glVertex2d( timeMin, sizeMax );
                        gl.glVertex2d( timeMax, sizeMax );
                        gl.glVertex2d( timeMax, sizeMin );
                    }
                    finally
                    {
                        gl.glEnd( );
                    }
                }
    
                if ( showBorder )
                {
                    GlimpseColor.glColor( gl, borderColor != null ? borderColor : painter.getBorderColor( ) );
                    gl.glLineWidth( borderThickness );
                    gl.glBegin( GL.GL_LINE_LOOP );
                    try
                    {
                        gl.glVertex2d( timeMin, sizeMin );
                        gl.glVertex2d( timeMin, sizeMax );
                        gl.glVertex2d( timeMax, sizeMax );
                        gl.glVertex2d( timeMax, sizeMin );
                    }
                    finally
                    {
                        gl.glEnd( );
                    }
                }
            }
            else
            {
                if ( showBackground )
                {
                    GlimpseColor.glColor( gl, backgroundColor != null ? backgroundColor : painter.getBackgroundColor( ) );
                    gl.glBegin( GL.GL_POLYGON );
                    try
                    {
                        gl.glVertex2d( arrowBaseMin, sizeMax );
                        gl.glVertex2d( arrowBaseMax, sizeMax );
                        gl.glVertex2d( timeMax, sizeCenter );
                        gl.glVertex2d( arrowBaseMax, sizeMin );
                        gl.glVertex2d( arrowBaseMin, sizeMin );
                        gl.glVertex2d( timeMin, sizeCenter );
                    }
                    finally
                    {
                        gl.glEnd( );
                    }
                }
                
                if ( showBorder )
                {
                    GlimpseColor.glColor( gl, borderColor != null ? borderColor : painter.getBorderColor( ) );
                    gl.glLineWidth( borderThickness );
                    gl.glBegin( GL.GL_LINE_LOOP );
                    try
                    {
                        gl.glVertex2d( arrowBaseMin, sizeMax );
                        gl.glVertex2d( arrowBaseMax, sizeMax );
                        gl.glVertex2d( timeMax, sizeCenter );
                        gl.glVertex2d( arrowBaseMax, sizeMin );
                        gl.glVertex2d( arrowBaseMin, sizeMin );
                        gl.glVertex2d( timeMin, sizeCenter );
                    }
                    finally
                    {
                        gl.glEnd( );
                    }
                }
            }

            isIconVisible = isIconVisible( size, buffer, remainingSpaceX, pixelX, nextStartPixel );

            if ( isIconVisible )
            {
                double valueX = axis.screenPixelToValue( pixelX );
                iconStartTime = epoch.toTimeStamp( valueX );
                iconEndTime = iconStartTime.add( size / axis.getPixelsPerValue( ) );

                TextureAtlas atlas = painter.getTextureAtlas( );
                atlas.beginRendering( );
                try
                {
                    ImageData iconData = atlas.getImageData( iconId );
                    double iconScale = size / ( double ) iconData.getHeight( );

                    atlas.drawImageAxisX( gl, iconId, axis, valueX, sizeMin, iconScale, iconScale, 0, iconData.getHeight( ) );
                }
                finally
                {
                    atlas.endRendering( );
                }

                remainingSpaceX -= size + buffer;
                pixelX += size + buffer;
            }

            TextRenderer textRenderer = painter.getTextRenderer( );
            Rectangle2D bounds = showName ? textRenderer.getBounds( name ) : null;

            isTextVisible = isTextVisible( size, buffer, remainingSpaceX, pixelX, nextStartPixel, bounds );

            if ( isTextVisible )
            {
                double valueX = axis.screenPixelToValue( pixelX );
                textStartTime = epoch.toTimeStamp( valueX );
                textEndTime = textStartTime.add( bounds.getWidth( ) / axis.getPixelsPerValue( ) );

                // use this event's text color if it has been set
                if ( textColor != null )
                {
                    GlimpseColor.setColor( textRenderer, textColor );
                }
                // otherwise, use the default no background color if the background is not showing
                // and if a color has not been explicitly set for the EventPainter
                else if ( !painter.textColorSet && !showBackground )
                {
                    GlimpseColor.setColor( textRenderer, painter.textColorNoBackground );
                }
                // otherwise use the EventPainter's default text color
                else
                {
                    GlimpseColor.setColor( textRenderer, painter.textColor );
                }
                
                textRenderer.beginRendering( width, height );
                try
                {
                    int pixelY = ( int ) ( size / 2.0 - bounds.getHeight( ) * 0.3 + sizeMin );
                    textRenderer.draw( name, pixelX, pixelY );

                    remainingSpaceX -= bounds.getWidth( ) + buffer;
                    pixelX += bounds.getWidth( ) + buffer;
                }
                finally
                {
                    textRenderer.endRendering( );
                }
            }
        }
        else
        {
            //TODO handle drawing text and icons in HORIZONTAL orientation

            GlimpseColor.glColor( gl, backgroundColor != null ? backgroundColor : painter.getBackgroundColor( ) );
            gl.glBegin( GL.GL_QUADS );
            try
            {
                gl.glVertex2d( sizeMin, timeMin );
                gl.glVertex2d( sizeMax, timeMin );
                gl.glVertex2d( sizeMax, timeMax );
                gl.glVertex2d( sizeMin, timeMax );
            }
            finally
            {
                gl.glEnd( );
            }

            GlimpseColor.glColor( gl, borderColor != null ? borderColor : painter.getBorderColor( ) );
            gl.glLineWidth( borderThickness );
            gl.glBegin( GL.GL_LINE_LOOP );
            try
            {
                gl.glVertex2d( sizeMin, timeMin );
                gl.glVertex2d( sizeMax, timeMin );
                gl.glVertex2d( sizeMax, timeMax );
                gl.glVertex2d( sizeMin, timeMax );
            }
            finally
            {
                gl.glEnd( );
            }
        }
    }

    protected boolean isTextVisible( int size, int buffer, double remainingSpaceX, int pixelX, int nextStartPixel, Rectangle2D bounds )
    {
        return showName && ( bounds.getWidth( ) + buffer < remainingSpaceX || !hideOverfull ) && ( pixelX + bounds.getWidth( ) + buffer < nextStartPixel || !hideIntersecting );
    }

    protected boolean isIconVisible( int size, int buffer, double remainingSpaceX, int pixelX, int nextStartPixel )
    {
        return showIcon && iconId != null && ( size + buffer < remainingSpaceX || !hideOverfull ) && ( pixelX + size + buffer < nextStartPixel || !hideIntersecting );
    }
    
    public void setToolTipText( String text )
    {
        this.toolTipText = text;
    }
    
    public String getToolTipText( )
    {
        return this.toolTipText;
    }
    
    public void setEditable( boolean isEditable )
    {
        this.isEditable = isEditable;
    }
    
    public boolean isEditable( )
    {
        return isEditable;
    }

    public boolean isEndTimeMoveable( )
    {
        return isEndTimeMoveable;
    }

    /**
     * If true, the endTime of the Event cannot be adjusted by user mouse gestures.
     */
    public void setEndTimeMoveable( boolean isEndTimeMoveable )
    {
        this.isEndTimeMoveable = isEndTimeMoveable;
    }

    public boolean isStartTimeMoveable( )
    {
        return isStartTimeMoveable;
    }

    /**
     * If true, the startTime of the Event cannot be adjusted by user mouse gestures. 
     */
    public void setStartTimeMoveable( boolean isStartTimeMoveable )
    {
        this.isStartTimeMoveable = isStartTimeMoveable;
    }

    public boolean isResizeable( )
    {
        return isResizeable;
    }

    /**
     * If true, the time span of the Event (the amount of time between the start and
     * end times) cannot be adjusted by user mouse gestures. However, the Event may
     * still be dragged. 
     */
    public void setResizeable( boolean isResizeable )
    {
        this.isResizeable = isResizeable;
    }

    public double getMaxTimeSpan( )
    {
        return maxTimeSpan;
    }

    /**
     * Sets the maximum time span between the start and end times. By default the
     * maximum is Double.MAX_VALUE.
     */
    public void setMaxTimeSpan( double maxTimeSpan )
    {
        this.maxTimeSpan = maxTimeSpan;
    }

    public double getMinTimeSpan( )
    {
        return minTimeSpan;
    }

    /**
     * Sets the minimum (inclusive) span between the start and end times. By default the
     * minimum is 0.
     */
    public void setMinTimeSpan( double minTimeSpan )
    {
        this.minTimeSpan = minTimeSpan;
    }

    public EventPlotInfo getEventPlotInfo( )
    {
        return info;
    }

    public void setEventPlotInfo( EventPlotInfo info )
    {
        this.info = info;
    }

    public String getLabel( )
    {
        return name;
    }

    public void setName( String name )
    {
        this.name = name;
    }

    public Object getIconId( )
    {
        return iconId;
    }

    public void setIconId( Object iconId )
    {
        this.iconId = iconId;
    }

    public void setBorderThickness( float thickness )
    {
        this.borderThickness = thickness;
    }

    public float[] getBackgroundColor( )
    {
        return backgroundColor;
    }

    public void setBackgroundColor( float[] backgroundColor )
    {
        this.backgroundColor = backgroundColor;
    }

    public float[] getBorderColor( )
    {
        return borderColor;
    }

    public void setBorderColor( float[] borderColor )
    {
        this.borderColor = borderColor;
    }

    public float[] getLabelColor( )
    {
        return textColor;
    }

    public void setLabelColor( float[] textColor )
    {
        this.textColor = textColor;
    }

    public TimeStamp getStartTime( )
    {
        return startTime;
    }

    public void setTimes( TimeStamp startTime, TimeStamp endTime, boolean force )
    {
        if ( !force )
        {
            TimeSpan newTimes = applyConstraints( new TimeSpan( startTime, endTime ) );
            startTime = newTimes.getStartTime( );
            endTime = newTimes.getEndTime( );
        }

        if ( this.info == null )
        {
            this.startTime = startTime;
            this.endTime = endTime;
        }
        else
        {
            // if we're attached to a plot, delegate the update of our
            // start/end time to it, so that it can update its data structures
            this.info.updateEvent( this, startTime, endTime );
        }
    }

    protected TimeSpan applyConstraints( TimeSpan span )
    {
        for ( EventConstraint constraint : constraints )
        {
            span = constraint.applyConstraint( this, span );
        }

        return span;
    }

    public void setTimes( TimeStamp startTime, TimeStamp endTime )
    {
        setTimes( startTime, endTime, false );
    }

    void setTimes0( TimeStamp startTime, TimeStamp endTime )
    {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public void setStartTime( TimeStamp startTime )
    {
        setTimes( startTime, this.endTime );
    }

    void setStartTime0( TimeStamp startTime )
    {
        this.startTime = startTime;
    }

    public TimeStamp getEndTime( )
    {
        return endTime;
    }

    public void setEndTime( TimeStamp endTime )
    {
        setTimes( this.startTime, endTime );
    }

    void setEndTime0( TimeStamp endTime )
    {
        this.endTime = endTime;
    }
    
    public TimeSpan getTimeSpan( )
    {
        return new TimeSpan( startTime, endTime );
    }

    public boolean isShowLabel( )
    {
        return showName;
    }

    public void setShowLabel( boolean showName )
    {
        this.showName = showName;
    }

    /**
     * If true, hides labels and/or icons if they would intersect with other events.
     */
    public void setHideIntersecting( boolean hide )
    {
        this.hideIntersecting = hide;
    }

    /**
     * If true, hides labels and/or icons if they would fall outside this event's time window.
     */
    public void setHideOverfull( boolean hide )
    {
        this.hideOverfull = hide;
    }

    public boolean isShowIcon( )
    {
        return showIcon;
    }

    public void setShowIcon( boolean showIcon )
    {
        this.showIcon = showIcon;
    }

    public boolean isShowBackground( )
    {
        return showBackground;
    }

    public void setShowBackground( boolean showBorder )
    {
        this.showBackground = showBorder;
    }
    
    public boolean isShowBorder( )
    {
        return showBorder;
    }

    public void setShowBorder( boolean showBorder )
    {
        this.showBorder = showBorder;
    }

    public Object getId( )
    {
        return id;
    }

    public boolean isIconVisible( )
    {
        return isIconVisible;
    }

    public boolean isLabelVisible( )
    {
        return isTextVisible;
    }

    public TimeStamp getIconStartTime( )
    {
        return iconStartTime;
    }

    public TimeStamp getIconEndTime( )
    {
        return iconEndTime;
    }

    public TimeStamp getLabelStartTime( )
    {
        return textStartTime;
    }

    public TimeStamp getLabelEndTime( )
    {
        return textEndTime;
    }

    @Override
    public int hashCode( )
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ( ( id == null ) ? 0 : id.hashCode( ) );
        return result;
    }

    @Override
    public boolean equals( Object obj )
    {
        if ( this == obj ) return true;
        if ( obj == null ) return false;
        if ( getClass( ) != obj.getClass( ) ) return false;
        Event other = ( Event ) obj;
        if ( id == null )
        {
            if ( other.id != null ) return false;
        }
        else if ( !id.equals( other.id ) ) return false;
        return true;
    }

    @Override
    public String toString( )
    {
        return String.format( "%s (%s)", name, id );
    }

    public static Event createDummyEvent( Event event )
    {
        TimeStamp startTime = TimeStamp.fromTimeStamp( event.getStartTime( ) );
        TimeStamp endTime = TimeStamp.fromTimeStamp( event.getEndTime( ) );
        return new Event( event.getId( ), null, startTime, endTime );
    }

    public static Event createDummyEvent( TimeStamp time )
    {
        return new Event( time );
    }

    public static Comparator<Event> getStartTimeComparator( )
    {
        return new Comparator<Event>( )
        {
            @Override
            public int compare( Event o1, Event o2 )
            {
                return o1.getStartTime( ).compareTo( o2.getStartTime( ) );
            }
        };
    }

    public static Comparator<Event> getEndTimeComparator( )
    {
        return new Comparator<Event>( )
        {
            @Override
            public int compare( Event o1, Event o2 )
            {
                return o1.getEndTime( ).compareTo( o2.getEndTime( ) );
            }
        };
    }
}
