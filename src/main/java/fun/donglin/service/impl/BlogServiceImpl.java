package fun.donglin.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import fun.donglin.entity.*;
import fun.donglin.mapper.BlogMapper;
import fun.donglin.mapper.BlogTagMapper;
import fun.donglin.mapper.TagMapper;
import fun.donglin.mapper.TypeMapper;
import fun.donglin.search.PageResult;
import fun.donglin.search.BlogParams;
import fun.donglin.service.BlogService;
import fun.donglin.util.MarkdownUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.mybatis.mapper.entity.Example;

import java.util.*;

@Service
public class BlogServiceImpl implements BlogService {

    @Autowired
    private BlogMapper blogMapper;

    @Autowired
    private TypeMapper typeMapper;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private BlogTagMapper blogTagMapper;

    private final Logger LOGGER = LoggerFactory.getLogger( this.getClass() );

    @Override
    public PageResult<Blog> findByPage(Integer page, Integer rows, String title, String typeName, Boolean commended, Boolean published) {
        PageHelper.startPage( page, rows );
        if (title != null) {
            title = "%" + title + "%";
        }
        Type type = null;
        if (StringUtils.isNoneBlank( typeName )) {
            Type record = new Type();
            record.setName( typeName );
            type = this.typeMapper.selectOne( record );
        }
        if (commended == null || !commended) {
            commended = null;
        }
        List<Blog> blogList = this.blogMapper.findAll( title, type == null ? null : type.getId(), commended, published );
        PageInfo<Blog> pageInfo = new PageInfo<>( blogList );
        return new PageResult<>( pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList() );
    }

    @Override
    public PageResult<Blog> findByPage(Integer page, Integer rows, List<String> tagNames) {
        PageHelper.startPage( page, rows );

        List<Blog> blogList = new ArrayList<>();

        if (CollectionUtils.isEmpty( tagNames )) {
            blogList = this.blogMapper.findAll( null, null, null, true );
        } else {
            // ??????????????????????????????id?????????
            List<Long> tagIds = new ArrayList<>();

            tagNames.forEach( tagName -> {
                Tag record = new Tag();
                record.setName( tagName );
                Tag tag = this.tagMapper.selectOne( record );
                tagIds.add( tag.getId() );
            } );

            // ??????
            Set<Long> set = new HashSet<>();
            tagIds.forEach( tagId -> {
                List<Long> bids = this.blogTagMapper.findBidsByTagId( tagId );
                set.addAll( bids );
            } );
            for (Long bid : set) {
                Blog foundBlog = this.blogMapper.findByIdAndPublished( bid );
                if (foundBlog != null) {
                    blogList.add( foundBlog );
                }
            }
        }
        PageInfo<Blog> pageInfo = new PageInfo<>( blogList );
        return new PageResult<>( pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList() );
    }

    @Override
    public List<Blog> findByDate(String date) {
        return this.blogMapper.findByDate( date );
    }

    @Override
    public List<Blog> findAll() {
        return this.blogMapper.selectAll();
    }

    @Override
    public Blog findById(Long id, Boolean flag) {
        Blog blog = this.blogMapper.findById( id );
        if (!flag) {
            return blog;
        }
        Blog b = new Blog();
        BeanUtils.copyProperties( blog, b );
        // markdown ??? html
        convert( b );
        return b;
    }

    @Override
    public List<Blog> findLatestThree(Boolean recommended) {
        // ????????? Example ??????
        Example example = new Example( Blog.class );
        Example.Criteria criteria = example.createCriteria();

        // ??????????????????
        example.excludeProperties( "content", "firstPicture" );

        // ????????????????????????
        criteria.andEqualTo( "published", true );

        // ????????????????????????
        if (recommended != null) {
            criteria.andEqualTo( "commended", recommended );
        }

        // ??????????????????
        example.setOrderByClause( "create_time " + " desc" );

        List<Blog> tempBlogList = this.blogMapper.selectByExample( example );

        if (CollectionUtils.isEmpty( tempBlogList ) || tempBlogList.size() <= 3) {
            return tempBlogList;
        }

        List<Blog> blogList = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            blogList.add( tempBlogList.get( i ) );
        }
        return blogList;
    }

    @Override
    @Transactional
    public void save(BlogParams blogParams) {
        Blog blog = blogParams.getBlog();
        String typeName = blogParams.getTypeName();
        List<String> tagNames = blogParams.getTagNames();

        assign( blog, typeName, tagNames );

        // ???blog???????????????
        this.blogMapper.insertSelective( blog );
        // ??????type
        this.blogMapper.updateTypeId( blog.getId(), blog.getType().getId() );
        // ??????user
        this.blogMapper.updateUserId( blog.getId(), blog.getUser().getId() );
        // ??????tags
        blog.getTags().forEach( tag -> this.blogTagMapper.insert( blogParams.getBlog().getId(), tag.getId() ) );
    }

    @Override
    public Blog findUnpublished() {
        return this.blogMapper.findUnpublished();
    }

    @Override
    @Transactional
    public boolean updateById(BlogParams blogParams) {
        Blog blog = blogParams.getBlog();
        assign( blog, blogParams.getTypeName(), blogParams.getTagNames() );
        return updateBlog( blog );
    }

    @Override
    @Transactional
    public boolean toggleTop(Long id) {
        try {
            Blog blog = this.blogMapper.findById( id );
            Boolean isTop = !blog.getTop();
            this.blogMapper.toggleTop( id, isTop );
        } catch (Exception e) {
            LOGGER.error( "???????????????????????? : {}", e.getMessage() );
            return false;
        }
        return true;
    }

    @Override
    @Transactional
    public Blog plusViews(Long id) {
        Blog blog;
        try {
            blog = this.findById( id, true );
            blog.setViews( blog.getViews() + 1 );
            this.blogMapper.updateViewsById( blog.getId() );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return blog;
    }

    @Override
    @Transactional
    public void delById(Long id) {
        // ?????????????????????tags??????
        this.blogTagMapper.delete( id );
        this.blogMapper.deleteByPrimaryKey( id );
    }

    private void assign(Blog blog, String typeName, List<String> tagNames) {
        // ??????type???????????????blog???
        Type typeRecord = new Type();
        typeRecord.setName( typeName );
        Type type = this.typeMapper.selectOne( typeRecord );
        blog.setType( type );

        // ??????tags???????????????blog???
        List<Tag> tags = new ArrayList<>();
        tagNames.forEach( tagName -> {
            Tag tagRecord = new Tag();
            tagRecord.setName( tagName );
            tags.add( this.tagMapper.selectOne( tagRecord ) );
        } );
        blog.setTags( tags );
    }

    private boolean updateBlog(Blog blog) {
        try {
            // ???blog???????????????
            this.blogMapper.updateByPrimaryKeySelective( blog );
            // ??????type
            this.blogMapper.updateTypeId( blog.getId(), blog.getType().getId() );
            // ??????tags
            this.blogTagMapper.delete( blog.getId() );
            blog.getTags().forEach( tag -> this.blogTagMapper.insert( blog.getId(), tag.getId() ) );
        } catch (Exception e) {
//            e.printStackTrace();
            LOGGER.error( "????????????????????????: {}", e.getMessage() );
            return false;
        }
        return true;
    }

    private void convert(Blog blog) {
        if (blog == null) {
            return;
        }
        blog.setContent( MarkdownUtils.markdownToHtmlExtensions( blog.getContent() ) );
    }
}
