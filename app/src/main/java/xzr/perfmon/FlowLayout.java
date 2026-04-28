package xzr.perfmon;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public class FlowLayout extends ViewGroup {
    private int mMaxWidth = Integer.MAX_VALUE;

    public FlowLayout(Context context) {
        super(context);
    }

    public FlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    
    public void setMaxWidth(int width) {
        mMaxWidth = width;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int sizeWidth = MeasureSpec.getSize(widthMeasureSpec);
        int modeWidth = MeasureSpec.getMode(widthMeasureSpec);
        
        // Use mMaxWidth if available, otherwise fallback to sizeWidth relative to spec
        int wrappingWidth;
        if (mMaxWidth != Integer.MAX_VALUE) {
            wrappingWidth = mMaxWidth;
        } else {
             if (modeWidth == MeasureSpec.UNSPECIFIED || sizeWidth == 0) {
                 wrappingWidth = Integer.MAX_VALUE;
             } else {
                 wrappingWidth = sizeWidth;
             }
        }

        int childState = 0;
        int width = 0;
        int height = 0;

        int rowWidth = 0;
        int rowHeight = 0;

        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE)
                continue;

            measureChild(child, widthMeasureSpec, heightMeasureSpec);

            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams(); 
            
            int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;

            // Simple Flow logic
            // Only wrap if we have at least one valid previous item (rowWidth > 0)
            if (rowWidth > 0 && rowWidth + childWidth > wrappingWidth && wrappingWidth != 0) { // Wrap
                width = Math.max(width, rowWidth);
                rowWidth = childWidth;
                height += rowHeight;
                rowHeight = childHeight;
            } else {
                rowWidth += childWidth;
                rowHeight = Math.max(rowHeight, childHeight);
            }
            childState = combineMeasuredStates(childState, child.getMeasuredState());
        }
        width = Math.max(width, rowWidth);
        height += rowHeight;

        // Force reported dimension to match content needs, allowing Window to expand.
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxWidth = r - l;
        
        if (mMaxWidth != Integer.MAX_VALUE && maxWidth > mMaxWidth) {
            maxWidth = mMaxWidth;
        }

        int left = 0;
        int top = 0;
        int rowHeight = 0;

        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE)
                continue;
                
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();

            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            
            int totalChildWidth = childWidth + lp.leftMargin + lp.rightMargin;
            int totalChildHeight = childHeight + lp.topMargin + lp.bottomMargin;

            // Only wrap if not first item in row
            if (left > 0 && left + totalChildWidth > maxWidth && maxWidth != 0) {
                left = 0;
                top += rowHeight;
                rowHeight = 0;
            }
            
            int childLeft = left + lp.leftMargin;
            int childTop = top + lp.topMargin;

            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
            left += totalChildWidth;
            rowHeight = Math.max(rowHeight, totalChildHeight);
        }
    }
}
