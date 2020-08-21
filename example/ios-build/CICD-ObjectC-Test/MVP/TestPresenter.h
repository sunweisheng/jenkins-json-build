//
//  Presenter.h
//  CICD-ObjectC-Test
//
//  Created by Test on 2020/8/17.
//  Copyright © 2020 Test. All rights reserved.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@protocol TestProtocol;
@interface TestPresenter : NSObject

@property (nonatomic, weak, readonly) id <TestProtocol> delegate;
- (instancetype)initWithDelegate:( nullable id <TestProtocol>)delegate;

/*!
 *  @method personClickTestButton
 *
 *  @discussion             点击 Test 按钮
 */

- (void)personClickTestButton;


/*!
 *  @method personClickCloseButton
 *
 *  @discussion             点击 Alert的 取消 按钮
 */
- (void)personClickCloseButton;
/*!
 *  @method personClickConfirmButton
 *
 *  @discussion             点击 Alert的 确定 按钮
 */
- (void)personClickConfirmButton;

@end

NS_ASSUME_NONNULL_END
