package com.osrsadvisor.plugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A rounded, collapsible card in the advisor list: header row (title, badge, chevron)
 * over a body the subclass builds. Left-click on the header toggles the body; a subclass
 * may attach a popup menu, shown from anywhere on the header row including the badge.
 *
 * The card paints its own rounded rectangle and is non-opaque, so the corners show the
 * panel background through - the list, anchor and viewport behind are all transparent.
 *
 * Long text is HTML with a fixed pixel width because that is the one way Swing reliably
 * wraps inside the ~225px sidebar. The width constants live here so every nesting level
 * derives from one place instead of hand-subtracting its own.
 */
abstract class CardPanel extends JPanel
{
	/** Panel is 225px; minus card padding and the scrollbar this is what text gets.
	 * Sized with slack on purpose - overrunning it clips the card's right edge. */
	static final int TEXT_PX = 164;
	/** Indented lines (training detail under a gate). */
	static final int INDENT_PX = 152;
	/** Text inside a rounded sub-block, which adds its own padding. */
	static final int SUB_PX = 138;
	/** Corner radius for cards; sub-blocks use {@code ARC - 2}. */
	static final int ARC = 10;

	static final Color GREEN = ColorScheme.PROGRESS_COMPLETE_COLOR;
	static final Color RED = ColorScheme.PROGRESS_ERROR_COLOR;
	static final Color AMBER = ColorScheme.PROGRESS_INPROGRESS_COLOR;
	static final Color DIM = ColorScheme.LIGHT_GRAY_COLOR;

	/** The emboss: a faint highlight along a card's top edge and a shade along its
	 * bottom, so the cards read as raised without a full border. */
	private static final Color EMBOSS_LIGHT = new Color(255, 255, 255, 26);
	private static final Color EMBOSS_SHADE = new Color(0, 0, 0, 70);

	/** The soft fonts. RuneLite's LAF defaults everything to the pixel font, so every
	 * component sets one of these explicitly; only the panel title keeps RuneScape bold. */
	static final Font BODY = FontManager.getDefaultFont().deriveFont(11f);
	static final Font TITLE = FontManager.getDefaultBoldFont().deriveFont(12f);
	/** Goal-box typography: two boxes share the sidebar's width, so tighter than BODY. */
	static final Font NUM = FontManager.getDefaultBoldFont().deriveFont(11f);
	static final Font SMALL = FontManager.getDefaultFont().deriveFont(10f);

	protected final JPanel body = new JPanel();
	private final JLabel chevron = new JLabel();
	private final Runnable onToggleExpand;
	private boolean expanded;

	CardPanel(boolean startExpanded, Runnable onToggleExpand)
	{
		this.expanded = startExpanded;
		this.onToggleExpand = onToggleExpand;
		setLayout(new BorderLayout());
		setOpaque(false);
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(9, 11, 9, 11));
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		paintCard(g, this);
		super.paintComponent(g);
	}

	/** The rounded fill plus emboss, shared with the goal boxes so every raised
	 * surface in the sidebar is painted by one hand. */
	static void paintCard(Graphics g, JPanel card)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(card.getBackground());
		g2.fillRoundRect(0, 0, card.getWidth(), card.getHeight(), ARC, ARC);
		g2.setColor(EMBOSS_LIGHT);
		g2.drawLine(ARC / 2, 0, card.getWidth() - 1 - ARC / 2, 0);
		g2.setColor(EMBOSS_SHADE);
		g2.drawLine(ARC / 2, card.getHeight() - 1,
			card.getWidth() - 1 - ARC / 2, card.getHeight() - 1);
		g2.dispose();
	}

	/** Subclasses call once, at the end of their constructor, fields already set. */
	protected void assemble(JLabel title, JLabel badge, JPopupMenu popup)
	{
		assemble(null, title, badge, popup);
	}

	/** As above, with a component leading the title - a quest card's icon. It gets no
	 * share of the header's mouse adapter on purpose: carrying its own listener it owns
	 * its clicks, and carrying none Swing hands them to the header - expand, as ever. */
	protected void assemble(Component west, JLabel title, JLabel badge, JPopupMenu popup)
	{
		add(header(west, title, badge, popup), BorderLayout.NORTH);
		buildBody();
		body.setVisible(expanded);
		add(body, BorderLayout.CENTER);
	}

	/** Fills {@link #body}; called once at assemble and again on {@link #rebuildBody()}. */
	protected abstract void buildBody();

	protected void rebuildBody()
	{
		body.removeAll();
		buildBody();
		revalidate();
		repaint();
	}

	private JPanel header(Component west, JLabel title, JLabel badge, JPopupMenu popup)
	{
		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		if (west != null)
		{
			header.add(west, BorderLayout.WEST);
		}
		header.add(title, BorderLayout.CENTER);

		JPanel right = new JPanel();
		right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
		right.setOpaque(false);
		if (badge != null)
		{
			right.add(badge);
			right.add(Box.createHorizontalStrut(4));
		}
		chevron.setText(expanded ? "▾" : "▸");
		chevron.setFont(BODY);
		chevron.setForeground(DIM);
		right.add(chevron);
		header.add(right, BorderLayout.EAST);

		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybePopup(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybePopup(e);
			}

			private void maybePopup(MouseEvent e)
			{
				if (popup != null && e.isPopupTrigger())
				{
					popup.show(e.getComponent(), e.getX(), e.getY());
				}
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					expanded = !expanded;
					body.setVisible(expanded);
					chevron.setText(expanded ? "▾" : "▸");
					onToggleExpand.run();
					revalidate();
					repaint();
				}
			}
		};
		header.addMouseListener(mouse);
		title.addMouseListener(mouse);
		right.addMouseListener(mouse);
		return header;
	}

	// --- shared building blocks -------------------------------------------

	/** A rounded inner block - the per-band sub-cards on a training step. */
	static JPanel roundedBlock(Color bg)
	{
		JPanel p = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(getBackground());
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC - 2, ARC - 2);
				g2.dispose();
				super.paintComponent(g);
			}
		};
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setBackground(bg);
		p.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	protected void addLine(JPanel target, String htmlText, int width, Color color)
	{
		addLine(target, htmlText, width, color, BODY);
	}

	/** SMALL is for description lines - notes, for-lines - a step under the body. */
	protected void addLine(JPanel target, String htmlText, int width, Color color,
		Font font)
	{
		JLabel label = html(htmlText, width, color);
		label.setFont(font);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		target.add(label);
	}

	/** Fixed-pixel body width is what makes Swing HTML actually wrap in the sidebar. */
	static JLabel html(String inner, int width, Color color)
	{
		String tint = color != null ? " color:" + hex(color) + ";" : "";
		return new JLabel("<html><body style='width:" + width + "px;" + tint + "'>"
			+ inner + "</body></html>");
	}

	static String hex(Color c)
	{
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	static String esc(String s)
	{
		return s == null ? ""
			: s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
